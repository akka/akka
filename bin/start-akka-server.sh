#!/bin/bash

VERSION=0.5

#if [ $# -gt 1 ];
#then
#  echo 'USAGE: bin/start-akka-server.sh'
#  exit 1
#fi

JAVA_HOME=/System/Library/Frameworks/JavaVM.framework/Versions/1.6.0/Home

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

CLASSPATH=$CLASSPATH:$BASE_DIR/kernel/target/classes
#CLASSPATH=$CLASSPATH:$BASE_DIR/dist/akka-kernel-$VERSION.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/scala-library-2.7.5.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/lib/configgy-1.3.jar
CLASSPATH=$CLASSPATH:$BASE_DIR/config

# To have Akka dump the generated classes, add the '-Daspectwerkz.transform.dump=*' option and it will dump classes to $BASE_DIR/_dump
JVM_OPTS=" \
        -server \
        -Xms128M \
        -Xmx1G \
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

echo "Starting up with options: 
"$JAVA_HOME/bin/java $JVM_OPTS -cp $CLASSPATH se.scalablesolutions.akka.Boot se.scalablesolutions.akka.kernel.Kernel ${1}

$JAVA_HOME/bin/java $JVM_OPTS -cp $CLASSPATH se.scalablesolutions.akka.Boot se.scalablesolutions.akka.kernel.Kernel ${1}
