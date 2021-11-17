FROM maven:3.6.1-jdk-8-alpine

ENV HDFS_URL "hdfs://hadoop-master:9000"
ENV CSV_FILE_PATH "/big-data/data.csv"

RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app

COPY pom.xml ./
CMD ls ./

RUN mvn dependency:resolve
RUN mvn verify


COPY ./ ./
RUN mvn clean 
RUN mvn package

CMD java -jar target/stream-producer-0.0.1-SNAPSHOT-jar-with-dependencies.jar

