#!/bin/bash
cd $AKKA_HOME
VERSION=akka_2.8.0.Beta1-0.8
TARGET_DIR=dist/$VERSION/$1
shift 1
VMARGS=$@

if [ -d $TARGET_DIR  ]; then
    cd $TARGET_DIR
else 
	unzip dist/${VERSION}.zip -d $TARGET_DIR
	cd $TARGET_DIR
fi

export AKKA_HOME=`pwd`
java -jar ${VMARGS} ${VERSION}.jar