#!/bin/bash

ssh-keyscan -H test-node1 >> ${HOME}/.ssh/known_hosts
ssh-keyscan -H test-node2 >> ${HOME}/.ssh/known_hosts
ssh-keyscan -H test-node3 >> ${HOME}/.ssh/known_hosts
ssh-keyscan -H test-node4 >> ${HOME}/.ssh/known_hosts
ssh-keyscan -H test-node5 >> ${HOME}/.ssh/known_hosts

kubectl exec --stdin --tty service/test-node1 -- /bin/bash -c 'truncate --size -1 /etc/hosts && echo " test-node1" >> /etc/hosts'
kubectl exec --stdin --tty service/test-node2 -- /bin/bash -c 'truncate --size -1 /etc/hosts && echo " test-node2" >> /etc/hosts'
kubectl exec --stdin --tty service/test-node3 -- /bin/bash -c 'truncate --size -1 /etc/hosts && echo " test-node3" >> /etc/hosts'
kubectl exec --stdin --tty service/test-node4 -- /bin/bash -c 'truncate --size -1 /etc/hosts && echo " test-node4" >> /etc/hosts'
kubectl exec --stdin --tty service/test-node5 -- /bin/bash -c 'truncate --size -1 /etc/hosts && echo " test-node5" >> /etc/hosts'
