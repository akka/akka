#!/bin/bash

if [ $# -gt 1 ];
then
	echo 'USAGE: bin/start-akka-server.sh [akka_home]'
	exit 1
fi

base_dir=$(dirname $0)/..

for file in $base_dir/lib/*.jar;
do
  CLASSPATH=$CLASSPATH:$file
done

java -Xmx1G -server -cp $CLASSPATH -Dcom.sun.management.jmxremote com.scalablesolutions.akka.Boot com.scalablesolutions.akka.kernel.Kernel ${1}
