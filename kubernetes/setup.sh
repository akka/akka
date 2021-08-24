#!/bin/bash
NUM_OF_NODES=$1
DEST_HOST_FILE=$2
TMP_DIR=.tmp

kubectl delete deployments,services -l app=multi-node-test | true
# TODO: wait for a proper condition
sleep 10

rm -rf ${TMP_DIR}
mkdir -p ${TMP_DIR}

touch ${DEST_HOST_FILE}

for i in `seq 1 "${NUM_OF_NODES}"`;
do
  cat ./kubernetes/test-node-base.yaml | sed "s/test-nodeX/test-node${i}/" > ".tmp/test-node${i}.yml"
  echo $i
  echo "test-node${i}:/usr/lib/jvm/java-11-openjdk-amd64/bin/java -Dmultinode.port=5000" >> ${DEST_HOST_FILE}
done

kubectl apply -f ${TMP_DIR}
# TODO: wait for a proper condition
sleep 10

# TODO: re-enable me
kubectl wait deploy/test-node1 --for condition=available --timeout=60s | true
kubectl wait --for=condition=Ready pods  --timeout=120s --all | true
