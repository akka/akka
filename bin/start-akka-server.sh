#!/bin/bash

VERSION=0.5

BASE_DIR=$(dirname $0)/..

echo 'Starting Akka Kernel from directory' $BASE_DIR

echo 'Resetting persistent storage in' $BASE_DIR/storage
rm -rf $BASE_DIR/storage
mkdir $BASE_DIR/storage
mkdir $BASE_DIR/storage/bootstrap
mkdir $BASE_DIR/storage/callouts
mkdir $BASE_DIR/storage/commitlog
mkdir $BASE_DIR/storage/data
mkdir $BASE_DIR/storage/system

CLASSPATH=$BASE_DIR/config:$BASE_DIR/deploy/root:$BASE_DIR/deploy/root/META-INF:$BASE_DIR/deploy/root/WEB-INF/classes
CLASSPATH=$CLASSPATH:$BASE_DIR/config
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/akka-kernel-0.5.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/akka-util-java-0.5.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/akka-util-java.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/antlr-3.1.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/aopalliance-1.0.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/asm-3.1.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/aspectwerkz-nodeps-jdk5-2.1.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/camel-core-2.0-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/cassandra-0.4.0-dev.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/cglib-2.2.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/commons-cli-1.1.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/commons-collections-3.2.1.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/commons-io-1.3.2.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/commons-javaflow-1.0-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/commons-lang-2.4.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/commons-logging-1.0.4.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/commons-math-1.1.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/configgy-1.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/google-collect-snapshot-20090211.jar
#CLASSPATH=$CLASSPATH:$BASE_DIR/lib/grizzly-framework-1.8.6.3.jar
#CLASSPATH=$CLASSPATH:$BASE_DIR/lib/grizzly-http-1.8.6.3.jar
#CLASSPATH=$CLASSPATH:$BASE_DIR/lib/grizzly-http-servlet-1.8.6.3.jar
#CLASSPATH=$CLASSPATH:$BASE_DIR/lib/grizzly-http-utils-1.8.6.3.jar
#CLASSPATH=$CLASSPATH:$BASE_DIR/lib/grizzly-servlet-webserver-1.8.6.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/grizzly-comet-webserver-1.9.15b.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/guice-core-2.0-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/guice-jsr250-2.0-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/high-scale-lib.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/jackson-core-asl-1.1.0.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/jackson-mapper-asl-1.1.0.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/jersey-client-1.1.0-ea.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/jersey-core-1.0.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/jersey-json-1.0.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/jersey-server-1.0.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/jersey-scala-1.1.2-ea-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/atmosphere-core-0.3-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/atmosphere-portable-runtime-0.3-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/atmosphere-compat-0.3-SNAPSHOT.jar
#CLASSPATH=$CLASSPATH:$BASE_DIR/lib/atmosphere-grizzly-adapter-0.3-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/JSAP-2.1.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/jsr250-api-1.0.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/jsr311-api-1.0.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/libfb303.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/libthrift.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/lift-webkit-1.1-M3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/lift-util-1.1-M3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/log4j-1.2.15.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/lucene-core-2.2.0.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/netty-3.1.0.CR1.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/protobuf-java-2.0.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/scala-library-2.7.5.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/servlet-api-2.5.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/slf4j-api-1.4.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/slf4j-log4j12-1.4.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/stringtemplate-3.0.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/zookeeper-3.1.0.jar

# Add for debugging: -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005 \
# To have Akka dump the generated classes, add the '-Daspectwerkz.transform.dump=*' option and it will dump classes to $BASE_DIR/_dump
JVM_OPTS=" \
        -server \
        -Xms128M \
        -Xmx2G \
        -XX:SurvivorRatio=8 \
        -XX:TargetSurvivorRatio=90 \
        -XX:+AggressiveOpts \
        -XX:+UseParNewGC \
        -XX:+UseConcMarkSweepGC \
        -XX:CMSInitiatingOccupancyFraction=1 \
        -XX:+CMSParallelRemarkEnabled \
        -XX:+HeapDumpOnOutOfMemoryError \
        -Dcom.sun.management.jmxremote.port=8080 \
        -Dcom.sun.management.jmxremote.ssl=false \
        -Dcom.sun.management.jmxremote.authenticate=false"

#$JAVA_HOME/bin/java $JVM_OPTS -cp $CLASSPATH se.scalablesolutions.akka.Boot se.scalablesolutions.akka.kernel.Kernel ${1}
$JAVA_HOME/bin/java $JVM_OPTS -cp $CLASSPATH se.scalablesolutions.akka.kernel.Kernel ${1}
