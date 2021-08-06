#!/bin/bash

rm -rf keys
mkdir -p keys

cp ${HOME}/.ssh/id_rsa.pub keys/id_rsa.pub

cp keys/id_rsa.pub keys/authorized_keys

kubectl delete secret ssh-keys | true

kubectl create secret generic ssh-keys \
--from-file=keys/authorized_keys
