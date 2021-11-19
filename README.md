## Struktura projekta

Projekat predstavlja aplikacije napisane u programskom jeziku Java za treniranje modela podataka za predikciju kašnjenja leta
na osnovu vremenskih uslova i aerodroma u Americi. Aplikacija se izvršava
na klasteru Docker kontejnera koji se pokreću na osnovu gotovih image-a. Struktura projekta se može videti u nastavku. 

Direktorijum `config`, kao i datoteke `Dockerfile`, `build-image.sh`, `LICENSE`, `README.md` i skripte `resize-cluster.sh` i `start-container.sh` vezane su za [korišćeni 
Hadoop klaster](https://github.com/kiwenlau/hadoop-cluster-docker). U fajlu `.project` nalazi se opis projekta, dok `docker-compose.yaml`, 
`docker-compose-model-training.yaml` i `docker-compose-stream-classification.yaml` služe za opis korišćenih servisa.
Folderi `stream-producer`, `batch-model-training` i `stream-classification` su kreirani u okruženju Eclipse kao Maven projekti. Oni sadrže folder `src/main/java/com/spark`,
 gde se nalazi odgovarajuća aplikacija (`App.java`), kao i pomoćne klase. Datoteka `.classpath` sadrži putanje korisnički definisanih klasa,
 paketa i resursa projekta, dok `.project` sadrži njegove build parametre. U datoteci `pom.xml` nalaze se informacije o projektu, kao što
 su servisi koji se koriste, dodaci za kompajliranje i dr. Folder `.settings` namenjen je preferencama projekta. Za pokretanje aplikacija u docker kontejneru dodate su i datoteke `Dockerfile` i `start.sh`.

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
## Korišćeni skup podataka

Podaci nad kojima treba izvršiti analizu preuzeti su sa [ove lokacije](https://www.kaggle.com/threnjen/2019-airline-delays-and-cancellations) kao csv dokument. 
Stavke dokumenta predstavljaju podatke prikupljene o praćenim letovima u Americi u periodu od januara do marta 2020, pri čemu je objašnjeno
 koja su značenja različitih tipova podataka koji su prisutni.
Prvu vrstu dokumenta čine nazivi odgovarajućih podataka, a ostale njihove vrednosti. 

## Infrastruktura

Aplikacija se izvršava na lokalnom klasteru Docker kontejnera. Hadoop klaster čini master i dva slave-a,
koji se pokreću u svojim kontejnerima (postupak opisan u `hadoop-cluster-docker/README.md`). 
Na kreiranoj mreži `hadoop` se pokreću i
ostali kontejneri, najpre spark master i worker, opisani u `docker-compose.yaml` 
([Big Data Europe](https://hub.docker.com/u/bde2020)), 
a zatim `cassandra` (zvanični docker image), kafka i zookeeper 
([wurstmeister](https://hub.docker.com/u/wurstmeister) image), `stream-producer`, `submit` (kontejner za treniranje modela) i `stream-classification`, 
definisani u `docker-compose-model-training.yaml` i `docker-compose-stream-classification.yaml`. Pre pokretanja aplikacije potrebno je uneti podatke u HDFS.

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


## Unos podataka

Pomenute podatke treba preuzeti [ovde](https://www.kaggle.com/threnjen/2019-airline-delays-and-cancellations) i prekopirati ih 
u Hadoop mnt direktorijum:

* `docker cp /<local_repo>/<data_file_name> hadoop-master:/mnt/data.csv`

Zatim u konzoli hadoop master-a treba kreirati folder big-data i uneti prethodno pomenutu datoteku:

* `hdfs dfs -mkdir /big-data`
* `hdfs dfs -put /mnt/data.csv /big-data/data.csv`

Sada je fajl spreman za preuzimanje sa HDFS-a i obradu.

## Pokretanje aplikacije

Nakon pokretanja hadoop kontejnera, treba pokrenuti najpre spark kontejnere:

* `docker-compose -f docker-compose.yaml up -d --build --force-recreate`

Zatim treba pokrenuti treniranje modela:

* `docker-compose -f docker-compose-model-training.yaml up -d --build --force-recreate`

Na kraju se kreirani model testira:

* `docker-compose -f docker-compose-stream-classification.yaml up -d --build --force-recreate`

## Rad aplikacije

Skripta `start.sh` u svakom od foldera aplikacija pokreće gotovu `template.sh` skriptu za pokretanje njihovih
kontejnera.

### Stream producer

Aplikacija `stream-producer` čita jednu po jednu liniju skupa podataka sa HDFS-a i šalje je na
Kafka `planes` topic.  

### Batch model training

Aplikacija `batch-model-training` služi da trenira ML model da predvidi da li će doći do otkazivanja leta u zavisnosti od parametara
kao što su mesec u godini, doba dana polaska, starost aviona, kiša, sneg i drugi.   
Korišćen je *random forest model*, sa kojim se postiže preciznost predviđanja od oko 87% i sačuvan na HDFS-u uz objekte koji obrađuju
preuzete podatke pre slanja modelu - indeksere. 

### Stream classification

Aplikacija `stream-classification` učitava model i indeksere sa HDFS-a i dodeljuje mu podatke preuzete sa `planes` Kafka topic-a, vrši klasifikaciju i
i prikazuje rezultate. 

## Nadgledanje rada

Izlaz koji aplikacije i servisi štampaju u konzoli može se videti preko prozora Docker Tooling u okruženju Eclipse, nakon povezivanja
na aktivnu socket konekciju i izbora željenog kontejnera.