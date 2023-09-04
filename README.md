# Использование Delta Lake в Yandex Data Proc

**Важное замечание.** Программное обеспечение [Delta Lake](https://delta.io) и кастомизации для него, предлагаемые в этом репозитории, не являются частью сервиса [Yandex Data Proc](https://cloud.yandex.ru/services/data-proc), и не сопровождаются командой разработки и службой поддержки этого сервиса.

## 1. Что такое Delta Lake и зачем он нужен

[Delta Lake](https://delta.io) - программное обеспечение с открытым исходным кодом, расширяющее возможности платформы [Apache Spark](https://spark.apache.org) оптимизированным слоем хранения данных в формате таблиц с поддержкой ACID-транзакций, масштабируемой обработкой метаданных и управлением версиями данных.

Delta Lake решает задачу обновления данных в аналитических таблицах, хранимых в виде набора файлов в формате Parquet в HDFS или S3-совместимом хранилище. Таблица Delta Lake может одновременно использоваться пакетными запросами, а также в качестве источника и приемника операций потоковой передачи данных.

## 2. Пре-реквизиты для эффективного использования Delta Lake в среде Data Proc

Таблицы Delta Lake удобно регистрировать в Hive Metastore, что позволяет работать в парадигме логических баз данных (или схем) и таблиц, без необходимости явно управлять размещением файлов. В среде Data Proc следует использовать внешний по отношению к рабочим кластерам Hive Metastore, например в форме управляемых экземпляров Metastore.

Для использования развёрнутых внешних экземпляров Metastore необходимо настроить на уровне кластера либо задания следующие свойства Spark:

* `spark.hive.metastore.uris`, установленное в значение `thrift://Хост:9083`, хде `Хост` - FDQN-имя либо IP-адрес сервиса Hive Metastore, а `9083` - используемый по умолчанию номер порта сервиса;
* `spark.sql.catalogImplementation`, установленное в значение `hive`. Альтернативой установке этого свойства является явный вызов операции `SparkSession.builder.enableHiveSupport()` при настройке контекста Spark.

Кроме того, для установки пути по умолчанию для размещения файлов Delta Lake в заданный путь внутри бакета объектного хранилища необходимо настроить следующее свойство:

* `spark.sql.warehouse.dir`, установленное в значение `s3a://Бакет/Путь`, где `Бакет` - используемое имя бакета объектного хранилища, а `Путь` - относительный путь внутри бакета (например, значение `warehouse`).

## 3. Delta Lake для стабильных образов Data Proc 2.0

Стабильные образы Data Proc (версии 2.0.x) основаны на Apache Spark 3.0.3. В соответствии с [официальной таблицей совместимости](https://docs.delta.io/latest/releases.html), в такой конфигурации может использоваться Delta Lake [версии 0.8.0](https://github.com/delta-io/delta/releases/tag/v0.8.0).

Основным ограничением этой версии Delta Lake является запрет на конкурентную модификацию таблиц в S3-совместимом хранилище (см. [примечание](https://docs.delta.io/0.8.0/delta-storage.html#amazon-s3) в официальной документации). Это ограничение означает, что при работе с таблицами Delta Lake необходимо координировать операции записи таким образом, чтобы исключить одновременную модификацию данных в одной таблице из разных кластеров Data Proc, а также из разных заданий Spark в рамках одного кластера. Нарушение этого требования может привести к неожиданной потере хранимой в таблице информации.

Для использования Delta Lake в сочетании с Data Proc 2.0 достаточно в зависимостях заданий добавить библиотеку `delta-core_2.12-0.8.0.jar`, а также установить следующие свойства Spark:

* `spark.sql.extensions` в значение `io.delta.sql.DeltaSparkSessionExtension`
* `spark.sql.catalog.spark_catalog` в значение `org.apache.spark.sql.delta.catalog.DeltaCatalog`

Библиотека `delta-core_2.12-0.8.0.jar` может быть добавлена в зависимости заданий любым из следующих способов:

1. через установку свойства `spark.jars` для конкретного задания либо для кластера в целом, значением является URL файла библиотеки, файл должен быть доступен по указанному URL;
2. через установку свойства `spark.jars.packages` в значение `io.delta:delta-core_2.12:0.8.0`, должен быть настроен сетевой доступ в Maven Central, либо сконфигурирован альтернативный репозиторий Maven;
3. через установку свойств `spark.driver.extraClassPath` и `spark.executor.extraClassPath` в полный путь к файлу библиотеки, файл должен быть [скопирован на все узлы кластера](../dataproc-copy-files/).

Если все данные заданий используют формат хранения Delta Lake, то настройка S3A Committers не требуется, поскольку Delta Lake реализует собственный алгоритм управления записью в S3-совместимые хранилища, функционально эквивалентный S3A Committers. Задание может одновременно работать с таблицами Delta Lake и "обычными" данными Spark в форматах Parquet, ORC, JSON или CSV; в этом случае для эффективной записи "обычных" данных необходимо [использовать S3A Committers](../dataproc-s3a-committers/).

Пример запуска и использования сессии Spark SQL для работы с таблицами Delta Lake:

```bash
$ spark-sql --executor-memory 20g --executor-cores 4 \
  --conf spark.executor.heartbeatInterval=10s \
  --conf spark.jars.packages=io.delta:delta-core_2.12:0.8.0 \
  --conf spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension \
  --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog
...
Ivy Default Cache set to: /home/ubuntu/.ivy2/cache
The jars for the packages stored in: /home/ubuntu/.ivy2/jars
:: loading settings :: url = jar:file:/usr/lib/spark/jars/ivy-2.4.0.jar!/org/apache/ivy/core/settings/ivysettings.xml
io.delta#delta-core_2.12 added as a dependency
:: resolving dependencies :: org.apache.spark#spark-submit-parent-d1295d4c-d10d-464c-aa11-e645cedd5da9;1.0
	confs: [default]
	found io.delta#delta-core_2.12;0.8.0 in central
	found org.antlr#antlr4;4.7 in central
...
downloading https://repo1.maven.org/maven2/io/delta/delta-core_2.12/0.8.0/delta-core_2.12-0.8.0.jar ...
	[SUCCESSFUL ] io.delta#delta-core_2.12;0.8.0!delta-core_2.12.jar (168ms)
...
23/02/26 14:57:37 INFO SharedState: Warehouse path is 's3a://dproc-wh/wh'.
23/02/26 14:57:38 INFO SessionState: Created HDFS directory: /tmp/hive/ubuntu
23/02/26 14:57:38 INFO SessionState: Created local directory: /tmp/ubuntu
23/02/26 14:57:38 INFO SessionState: Created HDFS directory: /tmp/hive/ubuntu/8e4fa862-2e74-4423-ad8a-241082ec2c88
23/02/26 14:57:38 INFO SessionState: Created local directory: /tmp/ubuntu/8e4fa862-2e74-4423-ad8a-241082ec2c88
23/02/26 14:57:38 INFO SessionState: Created HDFS directory: /tmp/hive/ubuntu/8e4fa862-2e74-4423-ad8a-241082ec2c88/_tmp_space.db
23/02/26 14:57:38 INFO SparkContext: Running Spark version 3.0.3
...
23/02/26 14:57:59 INFO metastore: Connected to metastore.
Spark master: yarn, Application Id: application_1677423247688_0004
23/02/26 14:58:00 INFO SparkSQLCLIDriver: Spark master: yarn, Application Id: application_1677423247688_0004
spark-sql> CREATE DATABASE testdelta;
Time taken: 4.248 seconds
spark-sql> USE testdelta;
Time taken: 0.056 seconds
spark-sql> CREATE TABLE tab1(a INTEGER NOT NULL, b VARCHAR(100)) USING DELTA;
23/02/26 15:04:07 INFO DeltaLog: Creating initial snapshot without metadata, because the directory is empty
...
23/02/26 15:04:09 INFO CreateDeltaTableCommand: Table is path-based table: false. Update catalog with mode: Create
23/02/26 15:04:09 WARN HiveExternalCatalog: Couldn't find corresponding Hive SerDe for data source provider delta. Persisting data source table `testdelta`.`tab1` into Hive metastore in Spark SQL specific format, which is NOT compatible with Hive.
Time taken: 3.286 seconds
spark-sql> INSERT INTO tab1 VALUES (1,'One'), (2,'Two'), (3,'Three');
...
Time taken: 3.684 seconds
spark-sql> UPDATE tab1 SET b=b || ' ** ' || CAST(a AS VARCHAR(10));
...
Time taken: 3.803 seconds
spark-sql> SELECT * FROM tab1;
3	Three ** 3
2	Two ** 2
1	One ** 1
Time taken: 1.166 seconds, Fetched 3 row(s)
spark-sql> 
```

При настройке перечисленных выше свойств Spark для использования Delta Lake на уровне кластера Data Proc, работа с таблицами Delta Lake может производиться через Spark Thrift Server, включение которого осуществляется свойством `dataproc:hive.thrift.impl=spark`, как указано в [документации Data Proc](https://cloud.yandex.ru/docs/data-proc/concepts/settings-list#spark-thrift-server). Для подключения к Spark Thrift Server может использоваться любая SQL IDE, совместипая с протоколом Thrift, например [DBeaver](https://dbeaver.io/).

## 4. Расширенные возможности Delta Lake для бета-образов Data Proc 2.1

Бета-образы Data Proc версии 2.1 комплектуются компонентами Apache Spark версии 3.2.1 (образы до версии 2.1.3 включительно) и 3.3.2 (образы версии 2.1.4 и более поздние). Чтобы получить доступ к бета-версиям образов Data Proc версии 2.1, запросите его через службу технической поддержки Yandex Cloud. Применительно к Delta Lake:

* Spark 3.2.1 (Data Proc 2.1.0-2.1.3) совместим с Delta Lake [версии 2.0.2](https://github.com/delta-io/delta/releases/tag/v2.0.2);
* Spark 3.3.2 (Data Proc 2.1.4+) совместим с Delta Lake [версии 2.3.0](https://github.com/delta-io/delta/releases/tag/v2.3.0).

Основными преимуществами Delta Lake 2.x, по сравнению с версией 0.8.0, является:

* поддержка [мультикластерного режима](https://docs.delta.io/2.0.2/delta-storage.html#multi-cluster-setup), обеспечивающего автоматическую координацию изменений данных в одной таблице из разных заданий Spark и кластеров Data Proc;
* функция [идемпотентных операций модификации данных](https://docs.delta.io/latest/delta-streaming.html#idempotent-table-writes-in-foreachbatch), позволяющая решить задачу однократного применения изменений при потоковой обработке данных;
* [функция Change Data Feed](https://docs.delta.io/2.0.2/delta-change-data-feed.html) для таблиц Delta Lake, обеспечивающая отслеживание изменений в данных;
* [функция Z-Ordering](https://docs.delta.io/2.0.2/optimizations-oss.html#z-ordering-multi-dimensional-clustering), реализующая многомерную кластеризацию таблиц Delta Lake для ускорения запросов с ограничениями на колонки, используемые для кластеризации;
* [поддержка динамической перезаписи партиций](https://docs.delta.io/2.0.2/delta-batch.html#dynamic-partition-overwrites), dynamic partition overwrite;
* автоматизированная [компактификация мелких файлов в более крупные](https://docs.delta.io/2.0.2/optimizations-oss.html#compaction-bin-packing) для увеличения производительности запросов;
* возможность [отката таблицы к предыдущему состоянию](https://docs.delta.io/2.0.2/delta-utility.html#restore-a-delta-table-to-an-earlier-state).

### 4.1. Однокластерный режим Delta Lake 2.x

При использовании однокластерного режима настройки для использования Delta Lake версии 2.0.2 аналогичны приведённым выше для версии 0.8.0. При настройке зависимостей необходимо учитывать, что в DeltaLake версии 1.2 из библиотеки `delta-core` была выделена дополнительная библиотека `delta-storage`. Состав необходимых зависимостей приведён в таблице ниже.

| Версия образа Data Proc | Версия DeltaLake | Зависимости Maven | Файлы JAR |
| - | - | - | - |
| 2.1.0 - 2.1.3 | 2.0.2 | `io.delta:delta-core_2.12:2.0.2`, `io.delta:delta-storage:2.0.2` | `delta-core_2.12-2.0.2.jar`, `delta-storage-2.0.2.jar` |
| 2.1.4+ | 2.3.0 | `io.delta:delta-core_2.12:2.3.0`, `io.delta:delta-storage:2.3.0` | `delta-core_2.12-2.3.0.jar`, `delta-storage-2.3.0.jar` |

### 4.2. Мультикластерный режим Delta Lake 2.x

Мультикластерный режим Delta Lake использует дополнительный ресурс - базу данных DynamoDB - для координации доступа к таблицам Delta Lake перед внесением в них изменений. В среде Yandex Cloud мультикластерный режим Delta Lake можно использовать, задействовав [управляемый сервис YDB](https://cloud.yandex.ru/services/ydb) в качестве прозрачной замены DynamoDB. Для использования YDB вместо DynamoDB требуется специальное расширение DeltaLake, которое подготовлено и доступно для скачивания.

> **Примечание.** Для выполнения приведённых ниже примеров команд требуется наличие установленного и настроенного [YC CLI](https://cloud.yandex.ru/docs/cli/operations/install-cli), а также утилита `jq` для обработки данных в формате JSON.

Если вы хотите задействовать мультикластерный режим Delta Lake, выполните перечисленные ниже шаги для подготовки среды выполнения:

1. Создайте бессерверную базу данных YDB, которая используется как замена DynamoDB для координации доступа к таблицам Delta Lake. Пример команд YC CLI по созданию базы данных, в результате выполнения которых информация о созданной базе данных будет сохранена в файле `info-delta1.json`:

    ```bash
    yc ydb database create delta1 --serverless
    yc ydb database get delta1 --format json >info-delta1.json
    ```

2. Создайте сервисную учётную запись для доступа к базе данных YDB, и настройте для неё права доступа путём присвоения роли `ydb.editor` в каталоге Облака, содержащем созданную ранее базу данных YDB:

    ```bash
    yc iam service-account create delta1-sa1

    cur_profile=`yc config profile list | grep -E ' ACTIVE$' | (read x y && echo $x)`
    cur_folder=`yc config profile get ${cur_profile} | grep -E '^folder-id: ' | (read x y && echo $y)`
    yc resource-manager folder add-access-binding --id ${cur_folder} --service-account-name delta1-sa1 --role ydb.editor
    ```

3. Создайте статический ключ для подключения от имени созданной сервисной учётной записи, временно сохранив данные ключа в файле `info-sa1key.json`:

    ```bash
    yc iam access-key create --service-account-name delta1-sa1 --format json > info-sa1key.json
    ```

4. Поместите данные созданного статического ключа в запись сервиса [Yandex Lockbox](https://cloud.yandex.ru/services/lockbox), установив атрибут `key-id` в идентификатор ключа, а атрибут `key-secret` в его секретную часть. Сохраните идентификационную информацию созданной записи в файле `info-lb1.json`:

    ```bash
    key_id=`cat info-sa1key.json | jq -r .access_key.key_id`
    key_val=`cat info-sa1key.json | jq -r .secret`
    yc lockbox secret create --name delta1-lb1 \
        --payload '[{"key": "key-id", "text_value": "'${key_id}'"}, {"key": "key-secret", "text_value": "'${key_val}'"}]' \
        --format json > info-lb1.json
    ```

5. Запишите идентификатор записи Lockbox для последующего использования:

    ```bash
    lb_id=`cat info-lb1.json | jq -r .id`
    ```

6. Разрешите сервисным учётным записям, используемым при работе кластеров Data Proc (в примере ниже - `dp1`), доступ к данным Lockbox (на уровне каталога в целом либо на уровне конкретной записи Lockbox):

    ```bash
    yc resource-manager folder add-access-binding --id ${cur_folder} --service-account-name dp1 --role lockbox.payloadViewer
    ```

7. Необходимые библиотеки Delta Lake 2.x, а также классы-надстройки для подключения к YDB доступны в виде собранных архивов для [DeltaLake 2.0.2](https://github.com/yandex-cloud/yc-delta/releases/download/v1.1/yc-delta20-multi-dp21-1.1-fatjar.jar) и [для DeltaLake 2.3.0](https://github.com/yandex-cloud/yc-delta/releases/download/v1.1/yc-delta23-multi-dp21-1.1-fatjar.jar) . Исходный код надстройки доступен в подкаталогах [yc-delta20](yc-delta20/) и [yc-delta23](yc-delta23/). Собранный архив необходимо разместить в бакете Yandex Object Storage. Права доступа к бакету должны обеспечивать возможность чтения файла архива сервисными учётными записями кластеров Data Proc.

8. Установите необходимые настройки для функционирования Delta Lake, на уровне отдельного задания либо на уровне кластера Data Proc:
    * зависимость от архива из пункта 7 выше, через свойство `spark.jars`, либо через сочетание свойств `spark.executor.extraClassPath` и `spark.driver.extraClassPath`;
    * `spark.sql.extensions` в значение `io.delta.sql.DeltaSparkSessionExtension`;
    * `spark.sql.catalog.spark_catalog` в значение `org.apache.spark.sql.delta.catalog.YcDeltaCatalog`;
    * `spark.delta.logStore.s3a.impl` в значение `ru.yandex.cloud.custom.delta.YcS3YdbLogStore`;
    * `spark.io.delta.storage.S3DynamoDBLogStore.ddb.endpoint` в значение точки подключения к Document API YDB для созданной на шаге (1) базы данных (значение поля `document_api_endpoint` из файла `info-delta1.json`);
    * `spark.io.delta.storage.S3DynamoDBLogStore.ddb.lockbox` в значение идентификатора элемента Lockbox, полученного на шаге (5).

9. Проверьте работоспособность Delta Lake, создав тестовую таблицу аналогично показанному ниже примеру:

    ```bash
    $ spark-sql --executor-memory 20g --executor-cores 4 \
      --jars yc-delta23-multi-dp21-1.1-fatjar.jar \
      --conf spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension \
      --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.YcDeltaCatalog \
      --conf spark.delta.logStore.s3a.impl=ru.yandex.cloud.custom.delta.YcS3YdbLogStore \
      --conf spark.io.delta.storage.S3DynamoDBLogStore.ddb.endpoint=https://docapi.serverless.yandexcloud.net/ru-central1/b1gfvslmokutuvt2g019/etngt3b6eh9qfc80vt54/ \
      --conf spark.io.delta.storage.S3DynamoDBLogStore.ddb.lockbox=e6qr20sbgn3ckpalh54p
    ...
    spark-sql> CREATE DATABASE testdelta;
    ...
    spark-sql> USE testdelta;
    ...
    spark-sql> CREATE TABLE tab1(a INTEGER NOT NULL, b VARCHAR(100)) USING DELTA;
    ...
    spark-sql> INSERT INTO tab1 VALUES (1,'One'), (2,'Two'), (3,'Three');
    ...
    spark-sql> SELECT * FROM tab1;
    ...
    ```

### 4.3. Производственные конфигурации мультикластерного режима

[Документация на DeltaLake](https://docs.delta.io/latest/delta-storage.html#production-configuration-s3-multi-cluster) содержит ряд рекомендаций по настройкам мультикластерного режима для производственных конфигураций. Ниже приведён вариант этих рекомендаций, адаптированный для работы в среде Yandex Cloud.

#### 4.3.1. Настроить пропускную способность базы данных YDB

По умолчанию бессерверная база данных YDB создаётся с ограниченной пропускной способностью (например, 10 Request Units в секунду). При интенсивной работе с DeltaLake эта пропускная способность может оказаться недостаточной. Рекомендуется контролировать достаточность пропускной способности по графику "Document API units overflow" в разделе мониторинга YDB, и при необходимости увеличить установленный предел пропускной способности.

Важно помнить, что на все базы данных YDB, созданные в определённом облаке, действует суммарное ограничение на пропускную способность, установленное в виде квоты на уровне облака. Соответствующее ограничение также может потребовать корректировки для исключения задержек при доступе со стороны DeltaLake к YDB.

#### 4.3.2. Настроить автоматическую чистку мусора

При работе DeltaLake в таблице YDB и в используемых бакетах объектного хранилища могут накапливаться старые неиспользуемые версии метаданных. Для повышения эффективности работы DeltaLake рекомендуется наладить регулярную автоматическую очистку этих ненужных метаданных.

Скрипт очистки мусора реализован в виде облачной (бессерверной) функции на языке Python. Для работы функции необходима сервисная учётная запись с правами, позволяющими получить доступ на чтение и запись в используемую базу данных YDB, а также в используемый бакет (или бакеты) объектного хранилища.

Код скрипта приведён в каталоге [cf-cleanup](./cf-cleanup/). Для установки скрипта подготовлены файлы командных сценариев, использующие [YC CLI](https://cloud.yandex.ru/docs/cli/quickstart):

* [ddb-maint-config.sh](./ddb-maint-config.sh) - параметры установки;
* [ddb-maint-setup.sh](./ddb-maint-setup.sh) - сценарий установки;
* [ddb-maint-remove.sh](./ddb-maint-remove.sh) - сценарий удаления.

Скрипт очистки устанавливается в виде двух облачных функций:

* функция очистки данных в таблице YDB, запускаемая автоматически один раз в час;
* функция очистки данных в объектном хранилище, запускаемая автоматически один раз в сутки.

Время жизни записей метаданных регулируется параметром Spark `spark.io.delta.storage.S3DynamoDBLogStore.ddb.ttl`, устанавливаемым в секундах. По умолчанию используется значение `86400`, соответствующее одним суткам. Фактическое время жизни для конкретной записи может оказаться больше, поскольку определяется моментом запуска скрипта очистки.

Для очистки временных файлов DeltaLake необходимо создать файл настроек и разместить его в объектном хранилище, указав полный путь к файлу настроек в переменной `s3_prefix_file` (см. параметры установки в [ddb-maint-config.sh](./ddb-maint-config.sh)). Каждая строка файла настроек определяет бакет, префикс для сканирования внутри бакета и режим сканирования в следующем формате: `ИмяБакета Режим ПрефиксПути`. Поле "Режим" может принимать одно из следующих значений:

* `W`, Warehouse - путь хранения для нескольких баз данных;
* `D`, Database - путь хранения одной базы данных;
* `T`, Table - путь хранения одной конкретной таблицы.

В зависимости от установленного режима меняется ожидаемая структура пути размещения временных файлов DeltaLake.

## 5. Полезная информация по настройке и применению Delta Lake

### 5.1. Количество параллельных заданий оператора OPTIMIZE

Оператор `OPTIMIZE` в Delta Lake 2.0.2 и выше запускает несколько параллельных заданий для объединения более мелких файлов в более крупные. Количество параллельных заданий регулируется свойством `spark.databricks.delta.optimize.maxThreads`, по умолчанию используется значение `10`. При обработке очень больших таблиц для ускорения можно использовать значительно большие значения, например `100` или даже `1000`, если ресурсы кластера позволяют запустить такое количество параллельных операций.

### 5.2. Синтаксис для конвертации партиционированных таблиц

Delta Lake поддерживает оператор `CONVERT TO DELTA` для преобразования обычных таблиц Spark SQL в формат Delta Lake. При работе с партиционированными таблицами у этого оператора используется специальный вариант синтаксиса с указанием колонок партиционирования, как показано в примере ниже:

```sql
-- Пример запроса для преобразования обычной партиционированной таблицы в формат Delta Lake
CONVERT TO DELTA megatab_1g PARTITIONED BY (tv_year INT, tv_month INT);
```

### 5.3. Пример операторов для генерации таблицы Delta Lake на 1 миллиард записей

В файле [delta-sample.sql](./delta-sample.sql) приведен набор команд, который может использоваться для проверки стабильности функционирования кластера Data Proc с библиотеками Delta Lake под нагрузкой, связанной с генерацией и записью значительного объёма данных.
