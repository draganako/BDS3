package com.spark;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.conf.Configuration;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

public class App 
{   
    public static final String PlaneTopic = "planes";

    public static void main( String[] args )
    {
        System.out.println("Producer starting");
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
        String hdfsUrl = System.getenv("HDFS_URL");
        if (hdfsUrl == null || hdfsUrl.equals("")) {
            throw new IllegalStateException("HDFS_URL environment variable must be set.");
        }
        String csvFilePath = System.getenv("CSV_FILE_PATH");
        if (csvFilePath == null || csvFilePath.equals("")) {
            throw new IllegalStateException("CSV_FILE_PATH environment variable must be set");
        }
        String kafkaUrl = System.getenv("KAFKA_URL");
        if (kafkaUrl == null || kafkaUrl.equals("")) {
            throw new IllegalStateException("KAFKA_URL environment variable must be set");
        }
        String publishIntervalInSec = System.getenv("PUBLISH_INTERVAL_IN_SEC");
        if (publishIntervalInSec == null || publishIntervalInSec.equals("")) {
            throw new IllegalStateException("PUBLISH_INTERVAL_IN_SEC environment variable must be set");
        }
        int publishInterval = Integer.parseInt(publishIntervalInSec);

        System.out.println("Producer started");

        KafkaProducer<String, String> producer = createProducer(kafkaUrl);
            
        FileSystem fs = null;
        FSDataInputStream inputStream = null;

        try {
            System.out.println("Opening HDFS");
            fs = openHDFS(hdfsUrl);
            System.out.println("Creating input stream");
            inputStream = createHDFSInputStream(fs, csvFilePath);

            String line = inputStream.readLine(); 
            line = inputStream.readLine(); // skip csv header

            while (line != null) {
                
                System.out.println("Creating ProducerRecord");
                ProducerRecord<String, String> rec = new ProducerRecord<String,String>(PlaneTopic, line);
                System.out.println("Sending");
                producer.send(rec);

                System.out.println("Publish to plane");

                Thread.sleep(publishInterval * 1000);

                line = inputStream.readLine();
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (fs != null) {
                    fs.close();
                }   
            } catch (IOException e) {
                System.out.println("Error: "+e.getMessage());
            }
        }

        producer.close();
        System.out.println("Producer has finsihed");
    }

    private static KafkaProducer<String, String> createProducer(String kafkaUrl) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUrl);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "stream-producer");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        return new KafkaProducer<>(props);
    }

    private static FileSystem openHDFS(String hdfsUrl) throws IOException {
        Configuration conf = new Configuration();
        conf.set(CommonConfigurationKeys.FS_DEFAULT_NAME_DEFAULT, hdfsUrl);
        conf.set("fs.hdfs.impl", DistributedFileSystem.class.getName());
        conf.set("fs.file.impl", LocalFileSystem.class.getName());
        
        return FileSystem.get(URI.create(hdfsUrl), conf);
    }

    private static FSDataInputStream createHDFSInputStream(FileSystem fs, String filePath)
     throws IOException {
        Path path = new Path(filePath);
        if (!fs.exists(path)) {
            throw new IOException("File not found on HDFS");
        }
        
        return fs.open(path);
    }

    private static String[] readLines(FSDataInputStream stream, int lineCount) throws IOException {
        int counter = 0;
        if (lineCount < 0)
            return  (String[]) new ArrayList<String>().toArray();

        List<String> result = new ArrayList<String>(lineCount);
        String line = stream.readLine();
        while (line != null && counter < lineCount) {
            result.add(line);
            line = stream.readLine();
            counter++;
        }
        
        return result.toArray(new String[0]);
    }
}
