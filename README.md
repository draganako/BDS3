## Project structure

The project represents Java applications that train a data model to predict flight delays based on 
weather conditions and airports in the United States, executed on a cluster of Docker containers and defined by open-source images.
The structure of the project can be seen below.

The `config` directory, as well as the files `Dockerfile`, `build-image.sh`, `LICENSE`, `README.md` and the scripts `resize-cluster.sh` and `start-container.sh` are linked to [used 
Hadoop cluster](https://github.com/kiwenlau/hadoop-cluster-docker). In the file `.project` there is a description of the project, while `docker-compose.yaml`, 
`docker-compose-model-training.yaml` and `docker-compose-stream-classification.yaml` serve to describe the services used.
The folders `stream-producer`, `batch-model-training` and `stream-classification` are created in the Eclipse environment as Maven projects. They contain the folder `src/main/java/com/spark`,
 where the corresponding application (`App.java`) is located, as well as the helper classes. The `.classpath` file contains user-defined class paths,
 package and project resources, while `.project` contains its build parameters. The `pom.xml` file contains data about the project, such as
 services used, plugins for compiling, etc. The `.settings` folder is intended for project preferences. `Dockerfile` and `start.sh` files have been added to run applications in the docker container.

```bash
hadoop-cluster-docker/
 |-- config/
 |   |--...
 |-- batch-model-training/
 |---- .settings/
 |     |--...
 |---- src/
 |------ main\java\com\spark/
 |       |--App.java
 |------ test/
 |       |--...
 |     .classpath
 |     .dockerignore
 |     .gitignore
 |     .project
 |     Dockerfile
 |     pom.xml
 |     start.sh
 |-- stream-classification/
 |---- .settings/
 |     |--...
 |---- src/
 |------ main\java\com\spark/
 |       |--App.java
 |       |--RowSchema.java
 |------ test/
 |       |--...
 |     .classpath
 |     .project
 |     Dockerfile
 |     pom.xml
 |     start.sh
 |-- stream-producer/
 |---- .settings/
 |     |--...
 |---- src/
 |------ main\java\com\spark/
 |       |--App.java
 |------ test/
 |       |--...
 |     .classpath
 |     .dockerignore
 |     .project
 |     Dockerfile
 |     pom.xml
 |     start.sh
 |build-image.sh
 |docker-compose.yaml
 |docker-compose-model-training.yaml
 |docker-compose-stream-classification.yaml
 |Dockerfile
 |LICENSE
 |README.md
 |resize-cluster.sh
 |start-container.sh
```
## Used dataset

The data to be analyzed was downloaded from [this location](https://www.kaggle.com/threnjen/2019-airline-delays-and-cancellations) as a CSV document. 
The document items represent data gathered on tracked flights in America between January and March 2020 with the definitions of the various data types present.
The first row of the document represents the names of the corresponding data and the other rows represent collected values.

## Infrastructure

The application runs on a local cluster of Docker containers. A Hadoop cluster consists of a master and two slaves,
that run in their containers (procedure described in `hadoop-cluster-docker/README.md`). 
The other containers run on the created `hadoop` network, primarily Spark master and worker, described in `docker-compose.yaml` 
([Big Data Europe](https://hub.docker.com/u/bde2020)), and then `kafka` and `zookeeper` ([wurstmeister](https://hub.docker.com/u/wurstmeister) image), `stream-producer`, `submit` (container for training models) and `stream-classification`, 
defined in `docker-compose-model-training.yaml` and `docker-compose-stream-classification.yaml`. Before running the application, the data must be loaded into HDFS.

**docker-compose.yaml:**

```
version: "3"

services:
  # SPARK
  spark-master:
    image: bde2020/spark-master:2.4.3-hadoop2.7
    container_name: spark-master
    ports:
      - "8080:8080"
      - "7077:7077"
    environment:
      - INIT_DAEMON_STEP=false
  spark-worker-1:
    image: bde2020/spark-worker:2.4.3-hadoop2.7
    container_name: spark-worker-1
    depends_on:
      - spark-master
    environment:
      - "SPARK_MASTER=spark://spark-master:7077"
      
networks:
  default:
    external:
      name: hadoop

```

**docker-compose-model-training.yaml:**

```
version: "3"

services:
  # BigData3 - Spark model-training
  submit:
    build: ./batch-model-training
    image: model-training:latest
    container_name: submit
    environment:
      HDFS_URL: hdfs://hadoop-master:9000
      APP_ARGS_CSV_FILE_PATH: /big-data/data.csv
      SPARK_MASTER_NAME: spark-master
      INDEXERS_PATH: /big-data/indexers/ # /big-data/indexers-lr/ 
      MODEL_PATH: /big-data/model/ # /big-data/logistic-reg/ 
      SPARK_MASTER_PORT: 7077
      SPARK_APPLICATION_ARGS: ""
      ENABLE_INIT_DAEMON: "false"
      
networks:
  default:
    external:
      name: hadoop
```

**docker-compose-stream-classification.yaml:**

```
version: "3"

services:
  # BigData3 - Spark stream classification
  stream-classification:
    build: ./stream-classification
    image: stream-classification:latest
    container_name: stream-classification
    depends_on: 
      - kafka
      - stream-producer
    environment:
      INITIAL_SLEEP_TIME_IN_SECONDS: 40
      SPARK_MASTER_NAME: spark-master
      SPARK_MASTER_PORT: 7077
      SPARK_APPLICATION_ARGS: ''
      INDEXERS_PATH: /big-data/indexers/
      MODEL_PATH: /big-data/model/
      HDFS_URL: hdfs://hadoop-master:9000
      KAFKA_URL: kafka:9092
      ENABLE_INIT_DAEMON: 'false'
      DATA_RECEIVING_TIME_IN_SECONDS: 30

  stream-producer:
    build: ./stream-producer
    image: stream-producer:latest
    container_name: stream-producer
    depends_on: 
      - kafka
    environment:
      INITIAL_SLEEP_TIME_IN_SECONDS: 20
      PUBLISH_INTERVAL_IN_SEC: 5
      HDFS_URL: hdfs://hadoop-master:9000
      CSV_FILE_PATH: /big-data/data.csv
      KAFKA_URL: kafka:9092

  # KAFKA
  zookeeper:
    image: wurstmeister/zookeeper:3.4.6
    container_name: zookeeper
    ports:
      - "2181:2181"
  kafka:
    image: wurstmeister/kafka:2.12-2.4.0
    container_name: kafka
    expose:
      - "9092"
    environment:
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_HOST_NAME: kafka
      
networks:
  default:
    external:
      name: hadoop
```


## Data input

The mentioned data should be downloaded [here](https://www.kaggle.com/threnjen/2019-airline-delays-and-cancellations) and copied 
to the Hadoop mnt directory:

* `docker cp /<local_repo>/<data_file_name> hadoop-master:/mnt/data.csv`

Then, in the hadoop master console, a big-data folder should be created with the previously mentioned file:

* `hdfs dfs -mkdir /big-data`
* `hdfs dfs -put /mnt/data.csv /big-data/data.csv`

Now the file is ready to be downloaded from HDFS and processed.

## Running the application

After starting the hadoop container, the spark containers should be started first:

* `docker-compose -f docker-compose.yaml up -d --build --force-recreate`

Then the model training should be executed:

* `docker-compose -f docker-compose-model-training.yaml up -d --build --force-recreate`

In the end, the testing of the created model should be executed:

* `docker-compose -f docker-compose-stream-classification.yaml up -d --build --force-recreate`

## Application execution

The `start.sh` script in each of the application folders runs ready-made `template.sh` scripts to start up the corresponding
containers.

### Stream producer

The `stream-producer` application reads the data from HDFS line by line and sends it to Kafka `planes` topic.

### Batch model training

The `batch-model-training` application is utilized to train an ML (random forest) model to predict whether a flight will be canceled, depending on parameters such as month of the year, day and time of departure, age of the aircraft, rain, snow and others. The model achieves a prediction accuracy of about 87% and is stored on HDFS with objects that process the downloaded data before sending it to the model – indexers.

### Stream classification

The `stream-classification` application loads the model and indexers from HDFS and assigns it data taken from the Kafka topic (`planes`), performs the classification and displays the results. 

## Application monitoring

The output that applications and services print to the console can be viewed through the Docker Tooling window in the Eclipse environment, after connecting
to the active socket connection and choosing the desired container.
