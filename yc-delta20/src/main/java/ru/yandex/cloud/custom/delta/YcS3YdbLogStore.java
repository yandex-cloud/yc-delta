package ru.yandex.cloud.custom.delta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.Configuration;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.ExpectedAttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;

import io.delta.storage.BaseExternalLogStore;
import io.delta.storage.CloseableIterator;
import io.delta.storage.ExternalCommitEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.yandex.cloud.custom.ddb.YcDdbDeltaFactory;
import static ru.yandex.cloud.custom.ddb.YcDdbDeltaFactory.ATTR_COMPLETE;
import static ru.yandex.cloud.custom.ddb.YcDdbDeltaFactory.ATTR_TABLE_PATH;
import static ru.yandex.cloud.custom.ddb.YcDdbDeltaFactory.ATTR_FILE_NAME;
import static ru.yandex.cloud.custom.ddb.YcDdbDeltaFactory.ATTR_TEMP_PATH;
import static ru.yandex.cloud.custom.ddb.YcDdbDeltaFactory.ATTR_EXPIRE_TIME;

/**
 * A customized implementation of {@link S3DynamoDBLogStore} that works with
 * YDB as a DynamoDB substitute in Yandex Cloud.
 *
 * Initially copied from Delta Lake 2.0.2, original code reference:
 * https://github.com/delta-io/delta/blob/v2.0.2/storage-s3-dynamodb/src/main/java/io/delta/storage/S3DynamoDBLogStore.java
 *
 * Adopted for YDB by Maksim Zinal.
 */
public class YcS3YdbLogStore extends BaseExternalLogStore {

    private static final Logger LOG = LoggerFactory.getLogger(YcS3YdbLogStore.class);

    // YcS3YdbLogStore Delta20 1.0 2023.07.14
    // YcS3YdbLogStore Delta20 1.1 SNAPSHOT
    public static final String VERSION = "YcS3YdbLogStore Delta20 1.2 2024.06.14";

    private final AmazonDynamoDB client;
    private final YcDdbDeltaFactory factory;

    public YcS3YdbLogStore(Configuration hadoopConf) throws IOException {
        super(hadoopConf);

        LOG.info("Activating {}", VERSION);

        this.factory = new YcDdbDeltaFactory(hadoopConf);
        this.client = this.factory.getClient();

        this.factory.tryEnsureTableExists(this.client);
    }

    /**
     * Dropping all the metadata which were created for the specified table path.
     * @param tablePath Table path to clean up.
     */
    public void removeMetadata(String tablePath) {
        final Map<String, Condition> conditions = new ConcurrentHashMap<>();
        conditions.put(
            ATTR_TABLE_PATH,
            new Condition()
                .withComparisonOperator(ComparisonOperator.EQ)
                .withAttributeValueList(new AttributeValue(tablePath))
        );

        final List<Map<String,AttributeValue>> items = client.query(
            new QueryRequest(factory.getTableName())
                .withConsistentRead(true)
                .withKeyConditions(conditions)
                .withAttributesToGet(ATTR_TABLE_PATH, ATTR_FILE_NAME)
        ).getItems();

        for (Map<String,AttributeValue> m : items) {
            LOG.debug("removeMetadata - Dropping item {}", m);
            client.deleteItem(factory.getTableName(), m);
        }
    }

    @Override
    public CloseableIterator<String> read(Path path, Configuration hadoopConf) throws IOException {
        // With many concurrent readers/writers, there's a chance that concurrent 'recovery'
        // operations occur on the same file, i.e. the same temp file T(N) is copied into the target
        // N.json file more than once. Though data loss will *NOT* occur, readers of N.json may
        // receive a RemoteFileChangedException from S3 as the ETag of N.json was changed. This is
        // safe to retry, so we do so here.
        final int maxRetries = factory.getMaxReadRetries();
        return new RetryableCloseableIterator(() -> super.read(path, hadoopConf), maxRetries);
    }

    @Override
    protected long getExpirationDelaySeconds() {
        return factory.getExpirationDelaySeconds();
    }

    @Override
    protected void putExternalEntry(
            ExternalCommitEntry entry,
            boolean overwrite) throws IOException {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("putItem %s, overwrite: %s", entry, overwrite));
            }
            client.putItem(createPutItemRequest(entry, overwrite));
        } catch (ConditionalCheckFailedException e) {
            LOG.debug("Conditional check failed", e);
            throw new java.nio.file.FileAlreadyExistsException(
                entry.absoluteFilePath().toString()
            );
        }
    }

    @Override
    protected Optional<ExternalCommitEntry> getExternalEntry(
            String tablePath,
            String fileName) {
        final Map<String, AttributeValue> attributes = new ConcurrentHashMap<>();
        attributes.put(ATTR_TABLE_PATH, new AttributeValue(tablePath));
        attributes.put(ATTR_FILE_NAME, new AttributeValue(fileName));

        Map<String, AttributeValue> item = client.getItem(
            new GetItemRequest(factory.getTableName(), attributes).withConsistentRead(true)
        ).getItem();

        return item != null ? Optional.of(dbResultToCommitEntry(item)) : Optional.empty();
    }

    @Override
    protected Optional<ExternalCommitEntry> getLatestExternalEntry(Path tablePath) {
        final Map<String, Condition> conditions = new ConcurrentHashMap<>();
        conditions.put(
            ATTR_TABLE_PATH,
            new Condition()
                .withComparisonOperator(ComparisonOperator.EQ)
                .withAttributeValueList(new AttributeValue(tablePath.toString()))
        );

        final List<Map<String,AttributeValue>> items = client.query(
            new QueryRequest(factory.getTableName())
                .withConsistentRead(true)
                .withScanIndexForward(false)
                .withLimit(1)
                .withKeyConditions(conditions)
        ).getItems();

        if (items.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(dbResultToCommitEntry(items.get(0)));
        }
    }

    /**
     * Map a DBB query result item to an {@link ExternalCommitEntry}.
     */
    private ExternalCommitEntry dbResultToCommitEntry(Map<String, AttributeValue> item) {
        final AttributeValue expireTimeAttr = item.get(ATTR_EXPIRE_TIME);
        return new ExternalCommitEntry(
            new Path(item.get(ATTR_TABLE_PATH).getS()),
            item.get(ATTR_FILE_NAME).getS(),
            item.get(ATTR_TEMP_PATH).getS(),
            item.get(ATTR_COMPLETE).getS().equals("true"),
            expireTimeAttr != null ? Long.parseLong(expireTimeAttr.getN()) : null
        );
    }

    private PutItemRequest createPutItemRequest(ExternalCommitEntry entry, boolean overwrite) {
        final Map<String, AttributeValue> attributes = new ConcurrentHashMap<>();
        attributes.put(ATTR_TABLE_PATH, new AttributeValue(entry.tablePath.toString()));
        attributes.put(ATTR_FILE_NAME, new AttributeValue(entry.fileName));
        attributes.put(ATTR_TEMP_PATH, new AttributeValue(entry.tempPath));
        attributes.put(
            ATTR_COMPLETE,
            new AttributeValue().withS(Boolean.toString(entry.complete))
        );

        if (entry.expireTime != null) {
            attributes.put(
                ATTR_EXPIRE_TIME,
                new AttributeValue().withN(entry.expireTime.toString())
            );
        }

        final PutItemRequest pr = new PutItemRequest(factory.getTableName(), attributes);

        if (!overwrite) {
            Map<String, ExpectedAttributeValue> expected = new ConcurrentHashMap<>();
            expected.put(ATTR_FILE_NAME, new ExpectedAttributeValue(false));
            pr.withExpected(expected);
        }

        return pr;
    }

}
