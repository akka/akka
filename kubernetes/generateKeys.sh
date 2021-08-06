#!/bin/bash

rm -rf keys
mkdir -p keys

cp ${HOME}/.ssh/id_rsa.pub keys/id_rsa.pub
cp ${HOME}/.ssh/id_rsa keys/id_rsa

cp keys/id_rsa.pub keys/authorized_keys

kubectl delete secret ssh-keys | true

kubectl create secret generic ssh-keys \
--from-file=keys/id_rsa \
--from-file=keys/id_rsa.pub \
--from-file=keys/authorized_keys
