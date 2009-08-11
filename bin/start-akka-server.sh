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

LIB_DIR=$BASE_DIR/lib

CLASSPATH=$BASE_DIR/config
CLASSPATH=$CLASSPATH:$LIB_DIR/akka-kernel-0.5.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/akka-util-java-0.5.jar

CLASSPATH=$CLASSPATH:$LIB_DIR/antlr-3.1.3.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/aopalliance-1.0.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/asm-3.1.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/aspectwerkz-nodeps-jdk5-2.1.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/atmosphere-core-0.3.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/atmosphere-portable-runtime-0.3.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/camel-core-2.0-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/atmosphere-compat-0.3.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/cassandra-0.4.0-dev.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/cglib-2.2.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/commons-cli-1.1.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/commons-collections-3.2.1.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/commons-io-1.3.2.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/commons-javaflow-1.0-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/commons-lang-2.4.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/commons-logging-1.0.4.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/commons-math-1.1.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/configgy-1.3.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/fscontext.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/google-collect-snapshot-20090211.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/grizzly-comet-webserver-1.8.6.3.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/guice-core-2.0-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/guice-jsr250-2.0-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/high-scale-lib.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/jackson-core-asl-1.1.0.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/jackson-mapper-asl-1.1.0.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/javautils-2.7.4-0.1.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/jersey-client-1.1.1-ea.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/jersey-core-1.1.1-ea.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/jersey-json-1.1.1-ea.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/jersey-server-1.1.1-ea.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/jersey-scala-1.1.2-ea-SNAPSHOT.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/JSAP-2.1.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/jsr250-api-1.0.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/jsr311-api-1.0.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/libfb303.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/libthrift.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/log4j-1.2.15.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/lucene-core-2.2.0.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/netty-3.1.0.GA.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/providerutil.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/protobuf-java-2.1.0.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/scala-library-2.7.5.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/servlet-api-2.5.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/slf4j-api-1.4.3.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/slf4j-log4j12-1.4.3.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/stringtemplate-3.0.jar
CLASSPATH=$CLASSPATH:$LIB_DIR/zookeeper-3.1.0.jar

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
        -Djava.naming.factory.initial=com.sun.jndi.fscontext.RefFSContextFactory \
        -Dcom.sun.grizzly.cometSupport=true \
        -Dcom.sun.management.jmxremote.authenticate=false"
 

#$JAVA_HOME/bin/java $JVM_OPTS -cp $CLASSPATH se.scalablesolutions.akka.Boot se.scalablesolutions.akka.kernel.Kernel ${1}
$JAVA_HOME/bin/java $JVM_OPTS -cp $CLASSPATH se.scalablesolutions.akka.kernel.Kernel ${1}
