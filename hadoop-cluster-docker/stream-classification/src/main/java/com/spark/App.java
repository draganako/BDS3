package com.spark;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.spark.ml.Pipeline;
import org.apache.spark.ml.classification.RandomForestClassificationModel;
import org.apache.spark.ml.feature.StringIndexerModel;
import org.apache.spark.ml.feature.VectorAssembler;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.ConsumerStrategies;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;


public class App {
    public static final String AccidentsKafkaTopic = "plane";

    public static void main(String[] args) {
        System.out.println("Stream classification starting");
        String initialSleepTime = System.getenv("INITIAL_SLEEP_TIME_IN_SECONDS");
        if (initialSleepTime != null && !initialSleepTime.equals("")) {
            int sleep = Integer.parseInt(initialSleepTime);
            System.out.println("Sleeping on start " + sleep + "sec");
            try {
                Thread.sleep(sleep * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        String sparkMasterUrl = System.getenv("SPARK_MASTER_URL");
        String kafkaUrl = System.getenv("KAFKA_URL");
        String hdfsUrl = System.getenv("HDFS_URL");
        String modelHdfsPath = System.getenv("MODEL_PATH");
        String indexersHdfsPath = System.getenv("INDEXERS_PATH");
        String dataReceivingTimeInSec = System.getenv("DATA_RECEIVING_TIME_IN_SECONDS");

        if (isNullOrEmpty(sparkMasterUrl)) {
            throw new IllegalStateException("SPARK_MASTER_URL environment variable must be set.");
        }
        if (isNullOrEmpty(kafkaUrl)) {
            throw new IllegalStateException("KAFKA_URL environment variable must be set");
        }
        if (isNullOrEmpty(hdfsUrl)) {
            throw new IllegalStateException("HDFS_URL environment variable must be set");
        }
        if (isNullOrEmpty(modelHdfsPath)) {
            throw new IllegalStateException("MODEL_PATH environment variable must be set");
        }
        if (isNullOrEmpty(indexersHdfsPath)) {
            throw new IllegalStateException("INDEXERS_PATH environment variable must be set");
        }
        if (isNullOrEmpty(dataReceivingTimeInSec)) {
            throw new IllegalStateException("DATA_RECEIVING_TIME_IN_SECONDS environment variable must be set");
        }

        int dataReceivingTime = Integer.parseInt(dataReceivingTimeInSec);
        String modelPath = hdfsUrl + modelHdfsPath;

        System.out.println("Stream classification started");

        RandomForestClassificationModel model = RandomForestClassificationModel.load(modelPath);
        System.out.println("Model loaded");

        final StringIndexerModel[] indexers;
        try {
            indexers = loadIndexers(hdfsUrl, indexersHdfsPath);
            System.out.println("Indexers laoded");
        } catch (IOException e1) {
            e1.printStackTrace();
            return;
        }

        SparkSession spark = SparkSession.builder().appName("Stream-Classification").master(sparkMasterUrl).getOrCreate();
        spark.sparkContext().setLogLevel("WARN");
        JavaSparkContext javaSparkContext = JavaSparkContext.fromSparkContext(spark.sparkContext());
        JavaStreamingContext streamingContext = new JavaStreamingContext(javaSparkContext, new Duration(dataReceivingTime * 1000));
        System.out.println("Spark started");

        Map<String, Object> kafkaParams = getKafkaParams(kafkaUrl);
        Collection<String> topics = Collections.singletonList(AccidentsKafkaTopic);
        JavaInputDStream<ConsumerRecord<Object, String>> stream = KafkaUtils.createDirectStream(streamingContext,
                LocationStrategies.PreferConsistent(), ConsumerStrategies.Subscribe(topics, kafkaParams));
        
        JavaDStream<Row> rowStream = stream.map((record) -> {
            return createRow(record.value());
        }).filter((r) -> !r.anyNull());
        
        String[] columnsToRemove = new String[]{"DEP_DEL15","DEP_BLOCK","CARRIER_NAME","DEPARTING_AIRPORT","PREVIOUS_AIRPORT"} ;
        String features = "Features";

        rowStream.foreachRDD((rowRdd) -> {
            if(rowRdd.count() == 0) {
                System.out.println("Row count zero, SKIPPING");
                return;
            }

            try {
                System.out.println("DATA PRINTING ___________________________");
                StructType st = RowSchema.getRowSchema();
                Dataset<Row> ds = spark.createDataFrame(rowRdd, st);
                ds.show();

                System.out.println("Loading pipes");
                Pipeline indexerPipeline = new Pipeline().setStages(indexers);
                System.out.println("Applying pipes to dataset");
                Dataset<Row> transformed = indexerPipeline.fit(ds).transform(ds);

                String[] columns = transformed.columns();
                String[] featureCols = removeColumns(columns, columnsToRemove);

                System.out.println("Columns: " + Arrays.toString(columns) + "//Features: " + Arrays.toString(featureCols));

                VectorAssembler vectorAssembler = new VectorAssembler()
                    .setInputCols(featureCols)
                    .setOutputCol(features);

                System.out.println("Applying vector assembler");
                Dataset<Row> assembled = vectorAssembler.transform(transformed);
                System.out.println("Predicting results");
                Dataset<Row> predictions = model.transform(assembled);

                System.out.println("showing results");
                predictions.show(10);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        streamingContext.start();

        try {
            streamingContext.awaitTermination();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static StringIndexerModel[] loadIndexers(String hdfsUrl, String indexerPath) 
    throws IOException {
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", hdfsUrl);

        FileSystem fs = FileSystem.newInstance(conf);
        Path hdfsPath = new Path(indexerPath);
        FileStatus[] fileStatuses = fs.listStatus(hdfsPath);

        List<StringIndexerModel> result = new ArrayList<StringIndexerModel>();

        for (FileStatus status: fileStatuses) {
            StringIndexerModel indexer = 
                StringIndexerModel.load(status.getPath().toString());
            indexer.setHandleInvalid("keep");
            result.add(indexer);
        }

        return result.toArray(new StringIndexerModel[0]);
    }

    private static Row createRow(String line) {
        String[] data = line.split(",");
        StructType st = RowSchema.getRowSchema();

        Object[] values = new Object[] {
            getIntOrNull(data[0]),
            getDoubleOrNull(data[2]),
             getDoubleOrNull(data[3]),
            data[4],
            data[8],
            getIntOrNull(data[16]),
            data[17],
            data[20],
            getDoubleOrNull(data[21]),
            getDoubleOrNull(data[22]),
            getDoubleOrNull(data[25])
            
        };

        return new GenericRowWithSchema(values, st);
    }

    private static Double getDoubleOrNull(String str) {
        Double res = null;
        try {
            res = Double.parseDouble(str);
        } catch (Exception e) {
            
        }
        return res;
    }
    
      private static Integer getIntOrNull(String str) {
        Integer res = null;
        try {
            res = Integer.parseInt(str);
        } catch (Exception e) {
            
        }
        return res;
    }

    private static String[] removeColumns(String[] columns, String[] colsToRemove) {

        List<String> colsList = new ArrayList<String>(Arrays.asList(columns));
        List<String> toRemove = Arrays.asList(colsToRemove);

        colsList.removeAll(toRemove);
        
        return colsList.toArray(new String[0]);
    }


    private static Map<String, Object> getKafkaParams(String kafkaUrl) {
        Map<String, Object> kafkaParams = new HashMap<String, Object>();
        kafkaParams.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUrl);
        kafkaParams.put(ConsumerConfig.CLIENT_ID_CONFIG, "streaming-consumer");
        kafkaParams.put(ConsumerConfig.GROUP_ID_CONFIG, "streaming-consumer");
        kafkaParams.put(ConsumerConfig.AUTO_OFFSET_RESET_DOC, "earliest");
        kafkaParams.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        kafkaParams.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        kafkaParams.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        kafkaParams.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return kafkaParams;
    }

    static boolean isNullOrEmpty(String str)
    {
        return str == null || str.isEmpty();
    }
}
