#!/bin/bash
NUM_OF_NODES=$1
DEST_HOST_FILE=$2
TMP_DIR=.tmp

kubectl delete deployments,services -l app=multi-node-test
kubectl wait --for=condition=Ready pods --all

rm -rf ${TMP_DIR}
mkdir -p ${TMP_DIR}

touch ${DEST_HOST_FILE}

for i in `seq 1 "${NUM_OF_NODES}"`;
do
  cat test-node-base.yaml | sed "s/test-nodeX/test-node${i}/" > ".tmp/test-node${i}.yml"
  echo $i
  echo "test-node${i}:/usr/lib/jvm/java-11-openjdk-amd64/bin/java -Dmultinode.port=5000" >> ${DEST_HOST_FILE}
done

kubectl apply -f ${TMP_DIR}
kubectl wait --for=condition=Ready pods --all
