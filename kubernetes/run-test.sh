#! /bin/bash

sudo telepresence --run sudo gosu runner sbt -jvm-opts .jvmopts-ci \
  -Dakka.test.timefactor=1 \
  -Dakka.cluster.assert=on \
  -Dsbt.override.build.repos=false \
  -Dakka.test.tags.exclude=gh-exclude \
  -Dakka.test.multi-node=true \
  -Dakka.test.multi-node.targetDirName=${PWD}/target/${JOB_ID} \
  -Dakka.test.multi-node.java=${JAVA_HOME}/bin/java \
  -Dmultinode.XX:MetaspaceSize=128M \
  -Dmultinode.XX:+UseCompressedOops \
  -Dmultinode.Xms256M \
  -Dmultinode.Xmx512M \
  -Dmultinode.server-port=4711 \
  -Dmultinode.max-nodes=1 \
  $1/multiNodeTest
