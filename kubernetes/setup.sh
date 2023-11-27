#!/bin/bash
NUM_OF_NODES=$1
DEST_HOST_FILE=$2
PROTOCOL=$3
TMP_DIR=.tmp

kubectl delete deployments,services -l app=multi-node-test | true

rm -rf ${DEST_HOST_FILE}
rm -rf ${TMP_DIR}
mkdir -p ${TMP_DIR}

touch ${DEST_HOST_FILE}

for i in `seq 1 "${NUM_OF_NODES}"`;
do
  cat ./kubernetes/test-node-base.yaml | sed "s/test-nodeX/test-node${i}/" > ".tmp/test-node${i}.yml"
  echo $i
  echo "test-node${i}:/opt/java/openjdk/bin/java -Dmultinode.protocol=$PROTOCOL -Dmultinode.port=5000 -Dmultinode.udp.port=6000" >> ${DEST_HOST_FILE}
done

kubectl apply -f ${TMP_DIR}
