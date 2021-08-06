#!/bin/bash

ssh -o StrictHostKeyChecking=no root@test-node1 uptime
ssh -o StrictHostKeyChecking=no root@test-node2 uptime
ssh -o StrictHostKeyChecking=no root@test-node3 uptime
ssh -o StrictHostKeyChecking=no root@test-node4 uptime
ssh -o StrictHostKeyChecking=no root@test-node5 uptime
