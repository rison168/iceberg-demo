package com.rison.iceberg.flink.cdc.oracle.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rison.iceberg.flink.util.KafkaUtil;
import com.ververica.cdc.connectors.oracle.OracleSource;
import com.ververica.cdc.connectors.oracle.table.StartupOptions;
import com.ververica.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.iceberg.*;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.flink.CatalogLoader;
import org.apache.iceberg.flink.FlinkCatalogFactory;
import org.apache.iceberg.flink.TableLoader;
import org.apache.iceberg.flink.sink.FlinkSink;
import org.apache.iceberg.types.Types;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @PACKAGE_NAME: com.rison.iceberg.flink.cdc.oracle.api
 * @NAME: Oracle2Iceberg
 * @USER: Rison
 * @DATE: 2022/4/24 12:55
 * @PROJECT_NAME: iceberg
 **/
public class Oracle2Iceberg {

    public static void main(String[] args) throws Exception {

        //TODO 0. 获取入参
        ParameterTool parameters = ParameterTool.fromArgs(args);
        String[] oracle_dbname_tablename_key_list = parameters.get("oracle_dbname_tablename_key_list", "FLINKUSER.oracle_source_tbl:ID;FLINKUSER.oracle_source_tbl_copy:ID").split(";");
        String oracleHostName = parameters.get("oracle_hostname", "172.16.16.67");
        int oraclePost = Integer.parseInt(parameters.get("oracle_post", "1521"));
        String oracleDataBase = parameters.get("oracle_database", "XE");
        String[] oracleSchemaList = parameters.get("oracle_schema_list", "flinkuser").split(",");
        String oracleUserName = parameters.get("oracle_username", "flinkuser");
        String oraclePassWord = parameters.get("oracle_password", "flinkpw");
        int checkpointInterval = Integer.parseInt(parameters.get("checkpoint_interval", "1"));
        String checkpointDataUri = parameters.get("checkpoint_data_uri", "hdfs:///flink/checkpoints-data/");
        String catalogWarehouseLocation = parameters.get("catalog_warehouse_location", "hdfs:///apps/hive/warehouse");
        String catalogUri = parameters.get("catalog_uri", "thrift://tbds-172-16-16-41:9083");
        String iceberg_default_db = parameters.get("iceberg_default_db", "iceberg_db");
        String iceberg_default_table = parameters.get("iceberg_default_table", "iceberg_default_table");

        //TODO 1. set flink env and set checkpoint
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        CheckpointConfig checkpointConfig = env.getCheckpointConfig();
        checkpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
        checkpointConfig.setCheckpointInterval(1_000L);
        checkpointConfig.setMinPauseBetweenCheckpoints(checkpointInterval * 1_1000L);
        checkpointConfig.setTolerableCheckpointFailureNumber(3);
        checkpointConfig.setCheckpointTimeout(60_1000L);
        checkpointConfig.setMaxConcurrentCheckpoints(1);
        checkpointConfig.enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        env.setStateBackend(new FsStateBackend(checkpointDataUri));
        env.getConfig().setAutoWatermarkInterval(5000L);
        env.setParallelism(1);

        Map<String, TableLoader> mapTableLoader = new CaseInsensitiveMap<String, TableLoader>();
        Map<String, String> mapPrimaryKey = new CaseInsensitiveMap<String, String>();
        //建立测输出流Map
        Map<String, OutputTag<Tuple2<String, String>>> mapOutPutTag = new org.apache.commons.collections.map.CaseInsensitiveMap();
        TableLoader defaultTableLoader = getTableLoad("hive_catalog", iceberg_default_db, iceberg_default_table, catalogUri, catalogWarehouseLocation);

        ArrayList<String> dbTableNameList = new ArrayList<>();
        for (String dbTableKey : oracle_dbname_tablename_key_list) {
            String[] arrOriginal = dbTableKey.split(":");
            String dbTableName = arrOriginal.length < 2 ? arrOriginal[arrOriginal.length - 1] : arrOriginal[arrOriginal.length - 2];
            String primaryKey = arrOriginal.length < 2 ? null : arrOriginal[arrOriginal.length - 1];
            if (dbTableName != null) {
                dbTableNameList.add(dbTableName);
            }
            String[] arr = dbTableName.split("\\.");
            String table = arr[arr.length - 1];
            String db = arr.length > 1 ? arr[arr.length - 2] : "iceberg_default_db";
            TableLoader tableLoader = getTableLoad("hive_catalog", db, table, catalogUri, catalogWarehouseLocation);

            mapTableLoader.put(dbTableName, tableLoader);
            mapPrimaryKey.put(dbTableName, primaryKey);
            //建立测输出流
            mapOutPutTag.put(dbTableName, new OutputTag<Tuple2<String, String>>(dbTableName) {
            });
        }
        //oracle-cdc tableList
        String[] dbTableNameArr = dbTableNameList.toArray(new String[dbTableNameList.size()]);

        System.out.println("设置的启动参数:"
                + "\n\t oracle_dbname_tablename_key_list " + oracle_dbname_tablename_key_list
                + "\n\t oracle_hostname:" + oracleHostName
                + "\n\t oracle_post:" + oraclePost
                + "\n\t oracle_database:" + oracleDataBase
                + "\n\t oracle_schema_list:" + oracleSchemaList
                + "\n\t oracle_username:" + oracleUserName
                + "\n\t oracle_password:" + oraclePassWord
                + "\n\t checkpoint_interval:" + checkpointInterval
                + "\n\t checkpoint_data_uri:" + checkpointDataUri
                + "\n\t catalog_warehouse_location:" + catalogWarehouseLocation
                + "\n\t catalog_uri:" + catalogUri
                + "\n\t iceberg_default_db:" + iceberg_default_db
                + "\n\t iceberg_default_table:" + iceberg_default_table
        );


        //TODO 2. source oracle-cdc
        SourceFunction<String> sourceFunction = OracleSource.<String>builder()
                .hostname(oracleHostName)
                .port(oraclePost)
                .database(oracleDataBase)
                .schemaList(oracleSchemaList)
                .tableList(dbTableNameArr)
                .username(oracleUserName)
                .password(oraclePassWord)
                .startupOptions(StartupOptions.initial())
                .deserializer(new JsonDebeziumDeserializationSchema())
                .build();

        DataStreamSource<String> dataStreamSource = env.addSource(sourceFunction);

        dataStreamSource
                .print().setParallelism(1);

        SingleOutputStreamOperator<Tuple2<String, String>> dbTableKeyDataStream = dataStreamSource.process(new ProcessFunction<String, Tuple2<String, String>>() {
            @Override
            public void processElement(String data, Context context, Collector<Tuple2<String, String>> collector) throws Exception {
                JSONObject jsonObject = JSONObject.parseObject(data);
                JSONObject sourceObject = jsonObject.getJSONObject("source");
                String dbTableName = sourceObject.getString("schema") + "." + sourceObject.getString("table");
                collector.collect(new Tuple2<>(oracleDataBase, data));
            }
        });

        SingleOutputStreamOperator<Tuple2<String, String>> sideOutputDataStream = dbTableKeyDataStream.process(new ProcessFunction<Tuple2<String, String>, Tuple2<String, String>>() {
            @Override
            public void processElement(Tuple2<String, String> data, Context context, Collector<Tuple2<String, String>> collector) throws Exception {
                OutputTag<Tuple2<String, String>> tuple2OutputTag = mapOutPutTag.get(data);
                //如果为null,说明入参没有这个db.tablename
                if (tuple2OutputTag != null) {
                    context.output(tuple2OutputTag, data);
                    return;
                }
                collector.collect(data);
            }
        });
        //TODO 3. 分流，sink到不同的iceberg表
        for (Map.Entry<String, TableLoader> entry : mapTableLoader.entrySet()) {
            try {
                DataStream<Tuple2<String, String>> dbTableDataStream = sideOutputDataStream.getSideOutput(mapOutPutTag.get(entry.getKey()));
                TableLoader tableLoader = mapTableLoader.get(entry.getKey());
                tableLoader.open();
                Table table = tableLoader.loadTable();
                TableOperations operations = ((BaseTable) table).operations();
                TableMetadata metadata = operations.current();
                operations.commit(metadata, metadata.upgradeToFormatVersion(2));

                Schema schema = table.schema();
                List<Types.NestedField> columns = schema.columns();
                if (columns == null || columns.size() < 1) {
                    throw new Exception(entry.getKey() + " columns null or zero (error)!");
                }

                SingleOutputStreamOperator<RowData> genericRowDataStream = dbTableDataStream.process(new ProcessFunction<Tuple2<String, String>, RowData>() {
                    @Override
                    public void processElement(Tuple2<String, String> data, Context context, Collector<RowData> collector) throws Exception {
                        JSONObject jsonObject = JSONObject.parseObject(data.f1);
                        JSONObject currentJsonObject = new JSONObject();
                        if (jsonObject.containsKey("op")) {
                            if ("c".equalsIgnoreCase(jsonObject.getString("op"))) {
                                currentJsonObject = jsonObject.getJSONObject("after");
                                currentJsonObject.put("current_ts", longTimeConvertString(Long.parseLong(jsonObject.getString("ts_ms"))));
                                currentJsonObject.put("op_ts", longTimeConvertString(Long.parseLong(jsonObject.getString("ts_ms"))));
                                collector.collect(jsonToRow(columns, RowKind.INSERT, currentJsonObject));
                            } else if ("d".equalsIgnoreCase(jsonObject.getString("op"))) {
                                currentJsonObject = jsonObject.getJSONObject("before");
                                currentJsonObject.put("current_ts", longTimeConvertString(Long.parseLong(jsonObject.getString("ts_ms"))));
                                currentJsonObject.put("op_ts", longTimeConvertString(Long.parseLong(jsonObject.getString("ts_ms"))));
                                collector.collect(jsonToRow(columns, RowKind.DELETE, currentJsonObject));
                            } else if ("u".equalsIgnoreCase(jsonObject.getString("op"))) {
                                if (jsonObject.containsKey("before")) {
                                    currentJsonObject = jsonObject.getJSONObject("before");
                                    collector.collect(jsonToRow(columns, RowKind.UPDATE_BEFORE, currentJsonObject));
                                }
                                if (jsonObject.containsKey("after")) {
                                    currentJsonObject = jsonObject.getJSONObject("after");
                                    currentJsonObject.put("current_ts", longTimeConvertString(Long.parseLong(jsonObject.getString("ts_ms"))));
                                    currentJsonObject.put("op_ts", longTimeConvertString(Long.parseLong(jsonObject.getString("ts_ms"))));
                                    collector.collect(jsonToRow(columns, RowKind.UPDATE_AFTER, currentJsonObject));
                                }
                            } else {
                                currentJsonObject = jsonObject.getJSONObject("after");
                                currentJsonObject.put("current_ts", longTimeConvertString(Long.parseLong(jsonObject.getString("ts_ms"))));
                                currentJsonObject.put("op_ts", longTimeConvertString(Long.parseLong(jsonObject.getString("ts_ms"))));
                                collector.collect(jsonToRow(columns, RowKind.INSERT, currentJsonObject));
                            }
                        }
                    }
                });

                String tablePrimaryKey = mapPrimaryKey.get(entry.getKey());

                //sink to Iceberg
                if (StringUtils.isNullOrWhitespaceOnly(tablePrimaryKey)) {
                    System.out.println(entry.getKey() + " 无逻辑主键");
                    FlinkSink.forRowData(genericRowDataStream)
                            .tableLoader(tableLoader)
                            .writeParallelism(1)
                            .build();
                } else {
                    System.out.println(entry.getKey() + " 有逻辑主键:" + tablePrimaryKey);
                    FlinkSink.forRowData(genericRowDataStream)
                            .tableLoader(tableLoader)
                            .equalityFieldColumns(Arrays.asList(tablePrimaryKey.split(",")))
                            .writeParallelism(1)
                            .build();
                }
                System.out.println(entry.getKey() + " sink to iceberg table ok ...");
                tableLoader.close();
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("[WARN] " + entry.getKey() + " sink to iceberg fail!");
            }
        }
        //TODO 4. 记录异常数据
        try {
            //默认的sink 到 默认表
            FlinkSink.forRowData(sideOutputDataStream.map(new MapFunction<Tuple2<String, String>, RowData>() {
                @Override
                public RowData map(Tuple2<String, String> data) throws Exception {
                    GenericRowData genericRowData = new GenericRowData(1);
                    genericRowData.setField(0, StringData.fromString(data.f1));
                    return genericRowData;
                }
            }))
                    .tableLoader(defaultTableLoader)
                    .writeParallelism(1)
                    .build();
            System.out.println(iceberg_default_db + "." + iceberg_default_table + " sink to iceberg table ok");
        } catch (Exception e) {
            System.out.println("[WARN] " + iceberg_default_db + "." + iceberg_default_table + "sink to iceberg table failed");
        }


        //TODO 5. 执行
        env.execute("Oracle2iceberg api");

    }

    private static TableLoader getTableLoad(String catalog, String db, String table, String catalogUri, String catalogWarehouseLocation) {
        try {
            Map<String, String> catalogProperties = new HashMap<>();
            catalogProperties.put("type", "iceberg");
            catalogProperties.put(FlinkCatalogFactory.ICEBERG_CATALOG_TYPE, FlinkCatalogFactory.ICEBERG_CATALOG_TYPE_HIVE);
            catalogProperties.put(CatalogProperties.WAREHOUSE_LOCATION, catalogWarehouseLocation);
            catalogProperties.put(CatalogProperties.URI, catalogUri);
            CatalogLoader catalogLoader = CatalogLoader.hive(catalog, getHadoopConfig(), catalogProperties);

            TableIdentifier identifier = TableIdentifier.of(Namespace.of(db), table);
            Catalog catalogObj = catalogLoader.loadCatalog();
            if (!catalogObj.tableExists(identifier)) {
                System.out.println("getTableLoad(" + catalog + "," + db + "," + table + ") not exists :");
                return null;
            }
            TableLoader tableLoader = TableLoader.fromCatalog(catalogLoader, identifier);
            return tableLoader;
        } catch (Exception e) {
            System.out.println("getTableLoad(" + catalog + "," + db + "," + table + ") exception :" + e.toString());
            e.printStackTrace();
            return null;
        }
    }

    public static Configuration getHadoopConfig() throws IOException {

        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", "hdfs://hdfsCluster");
        conf.addResource(new Path("/usr/hdp/current/hadoop-client/etc/hadoop/hdfs-site.xml"));
        conf.addResource(new Path("/usr/hdp/current/hadoop-client/etc/hadoop/core-site.xml"));
        conf.addResource(new Path("/usr/hdp/current/hive-client/conf/hive-site.xml"));
        conf.set("fs.hdfs.impl", "org.apache.hadoop.hdfs.DistributedFileSystem");
        conf.setBoolean("fs.hdfs.impl.disable.cache", true);
        UserGroupInformation.setConfiguration(conf);
        UserGroupInformation.loginUserFromSubject(null);
        return conf;
    }

    public static GenericRowData jsonToRow(List<Types.NestedField> columns, RowKind rowKind, JSONObject jsonObject) {
        GenericRowData result = new GenericRowData(rowKind, columns.size());
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            try {
                Types.NestedField column = columns.get(columnIndex);
                if (!jsonObject.containsKey(column.name())) {
                    //jsonObject中没这个字段值，就跳过不处理
                    continue;
                }
                if (Types.IntegerType.get().toString().equals(column.type().toString())) {
                    result.setField(columnIndex, jsonObject.getIntValue(column.name()));

                } else if (Types.LongType.get().toString().equals(column.type().toString())) {
                    result.setField(columnIndex, jsonObject.getLong(column.name()));

                } else if (Types.FloatType.get().toString().equals(column.type().toString())) {
                    result.setField(columnIndex, Float.parseFloat(jsonObject.get(column.name()).toString()));

                } else if (Types.DoubleType.get().toString().equals(column.type().toString())) {
                    result.setField(columnIndex, jsonObject.getDouble(column.name()));

                } else if (Types.TimestampType.withoutZone().toString().equals(column.type().toString()) ||
                        Types.TimestampType.withZone().toString().equals(column.type().toString())) {
                    result.setField(columnIndex, getTimeStampData(jsonObject.getString(column.name())));

                } else {
                    //TODO 其它类型先全当做string处理
                    result.setField(columnIndex, StringData.fromString(jsonObject.getString(column.name())));
                }
            } catch (Exception e) {
                System.out.println(columnIndex + "to row exception:" + e.toString());
                e.printStackTrace();
            }
        }
        System.out.println("result:\t" + result.toString());
        return result;
    }

    public static TimestampData getTimeStampData(String timeStr) throws ParseException {
        String dateStr = timeStr.replace("T", " ");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long timestamp = format.parse(dateStr).getTime();
        return TimestampData.fromEpochMillis(timestamp);
    }

    public static String longTimeConvertString(long time){
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timeStr = format.format(new Date(time));
        return timeStr;
    }
}
