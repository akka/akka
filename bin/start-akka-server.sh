#!/bin/bash

if [ $# -gt 1 ];
then
	echo 'USAGE: bin/start-akka-server.sh [akka_home]'
	exit 1
fi

BASE_DIR=$(dirname $0)/..

echo 'Starting Akka Kernel from directory' $BASE_DIR

for FILE in $BASE_DIR/lib/*.jar;
do
  CLASSPATH=$CLASSPATH:$FILE
done
CLASSPATH=$CLASSPATH:$BASE_DIR/config
CLASSPATH=$CLASSPATH:$BASE_DIR/kernel/target/classes

STORAGE_OPTS=" \
        -Dcassandra \
        -Dstorage-config=$BASE_DIR/config/ \
        -Dpidfile=akka.pid"

JVM_OPTS=" \
        -server \
        -Xdebug \
        -Xrunjdwp:transport=dt_socket,server=y,address=8888,suspend=n \
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

/System/Library/Frameworks/JavaVM.framework/Versions/1.6.0/Home/bin/java $JVM_OPTS $STORAGE_OPTS -cp $CLASSPATH se.scalablesolutions.akka.Boot se.scalablesolutions.akka.kernel.Kernel ${1}