package ru.yandex.cloud.custom.ddb;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;

/**
 * Factory class to apply the YDB specific settings used by the multi-cluster DeltaLake installations.
 *
 * @author mzinal
 */
public class YcDdbDeltaFactory implements AWSCredentialsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(YcDdbDeltaFactory.class);

    public static final String MISSING = "MISSING";

    /**
     * Configuration keys for the DynamoDB client.
     *
     * Keys are either of the form $SPARK_CONF_PREFIX.$CONF or $BASE_CONF_PREFIX.$CONF,
     * e.g. spark.io.delta.storage.S3DynamoDBLogStore.ddb.tableName
     * or io.delta.storage.S3DynamoDBLogStore.ddb.tableName
     */
    public static final String SPARK_CONF_PREFIX = "spark.io.delta.storage.S3DynamoDBLogStore";
    public static final String BASE_CONF_PREFIX = "io.delta.storage.S3DynamoDBLogStore";
    public static final String READ_RETRIES = "read.retries";
    public static final String DDB_ENDPOINT = "ddb.endpoint";
    public static final String DDB_LOCKBOX = "ddb.lockbox";
    public static final String DDB_CLIENT_TABLE = "ddb.tableName";
    public static final String DDB_CLIENT_REGION = "ddb.region";

    // WARNING: setting this value too low can cause data loss. Defaults to a duration of 1 day.
    public static final String TTL_SECONDS = "ddb.ttl";

    /**
     * S3 keys defined explicitly
     */
    public static final String ROOT_SPARK_PREFIX = "spark";
    public static final String ROOT_HADOOP_PREFIX = "spark.hadoop";
    public static final String S3_KEY_NAME = "fs.s3a.access.key";
    public static final String S3_KEY_SECRET = "fs.s3a.secret.key";

    private final String tableName;
    private final String regionName;
    private final String endpoint;
    private final String lockboxEntry;
    private final String s3directId;
    private final String s3directSecret;
    private final long expirationDelaySeconds;
    private final int maxReadRetries;

    public YcDdbDeltaFactory(Configuration hc) {
        tableName = getParam(hc, DDB_CLIENT_TABLE, "delta_log");
        regionName = getParam(hc, DDB_CLIENT_REGION, "ru-central1");
        endpoint = getParam(hc, DDB_ENDPOINT, MISSING);
        s3directId = getHadoopParam(hc, S3_KEY_NAME, null);
        s3directSecret = getHadoopParam(hc, S3_KEY_SECRET, null);
        if (s3directId != null && s3directSecret != null) {
            // We ignore the lockbox entry, if the explicit S3 key is provided.
            // In this case the S3 key will be used for DynamoDB access.
            lockboxEntry = null;
        } else {
            lockboxEntry = getParam(hc, DDB_LOCKBOX, MISSING);
        }

        final String ttl = getParam(hc, TTL_SECONDS, null);
        expirationDelaySeconds = ttl == null
                ? TimeUnit.DAYS.toSeconds(1)
                : Long.parseLong(ttl);
        if (expirationDelaySeconds < 0) {
            throw new IllegalArgumentException(
                    String.format(
                            "Can't use negative `%s` value of %s", TTL_SECONDS, expirationDelaySeconds));
        }

        maxReadRetries = Integer.parseInt(
            getParam(hc, READ_RETRIES, "4")
        );
        if (maxReadRetries < 1) {
            throw new IllegalArgumentException(
                    String.format(
                            "Can't use negative `%s` value of %s", READ_RETRIES, maxReadRetries));
        }

        LOG.info("using tableName {}", tableName);
        LOG.info("using regionName {}", regionName);
        LOG.info("using endpoint {}", endpoint);
        if (lockboxEntry != null) {
            LOG.info("using lockboxEntry {}", lockboxEntry);
        } else {
            LOG.info("using S3 direct key {}", s3directId);
        }
        LOG.info("using ttl (seconds) {}", expirationDelaySeconds);
    }

    public String getTableName() {
        return tableName;
    }

    public int getMaxReadRetries() {
        return maxReadRetries;
    }

    public long getExpirationDelaySeconds() {
        return expirationDelaySeconds;
    }

    public String getRegionName() {
        return regionName;
    }

    protected static String getParam(Configuration hc, String sparkPrefix, String basePrefix,
            String name, String defaultValue) {
        final String sparkPrefixKey = String.format("%s.%s", sparkPrefix, name);
        final String basePrefixKey = String.format("%s.%s", basePrefix, name);

        final String sparkPrefixVal = hc.get(sparkPrefixKey);
        final String basePrefixVal = hc.get(basePrefixKey);

        if (sparkPrefixVal != null &&
            basePrefixVal != null &&
            !sparkPrefixVal.equals(basePrefixVal)) {
            throw new IllegalArgumentException(
                String.format(
                    "Configuration properties `%s=%s` and `%s=%s` have different values. " +
                    "Please set only one.",
                    sparkPrefixKey, sparkPrefixVal, basePrefixKey, basePrefixVal
                )
            );
        }

        if (sparkPrefixVal != null) return sparkPrefixVal;
        if (basePrefixVal != null) return basePrefixVal;
        return defaultValue;
    }

    protected static String getParam(Configuration hc, String name, String defaultValue) {
        return getParam(hc, SPARK_CONF_PREFIX, BASE_CONF_PREFIX, name, defaultValue);
    }

    protected static String getHadoopParam(Configuration hc, String name, String defaultValue) {
        return getParam(hc, ROOT_SPARK_PREFIX, ROOT_HADOOP_PREFIX, name, defaultValue);
    }

    /**
     * Yandex Cloud and YDB specific configuration for DynamoDB client.
     *
     * @return The configured DynamoDB client instance.
     */
    public AmazonDynamoDB getClient() {
        return AmazonDynamoDBClientBuilder.standard()
                .disableEndpointDiscovery()
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(endpoint, regionName))
                .withCredentials(makeCredentials())
                .build();
    }

    private AWSCredentialsProvider makeCredentials() {
        if (lockboxEntry != null && !MISSING.equalsIgnoreCase(lockboxEntry)) {
            return new YcLockboxAwsCredentials(lockboxEntry);
        } else {
            return this;
        }
    }

    @Override
    public AWSCredentials getCredentials() {
        return new BasicAWSCredentials(s3directId, s3directSecret);
    }

    @Override
    public void refresh() {
        /* noop */
    }

}
