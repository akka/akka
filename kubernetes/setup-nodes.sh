#!/bin/bash
kubectl delete deployments,services -l app=multi-node-test1
kubectl delete deployments,services -l app=multi-node-test2
kubectl delete deployments,services -l app=multi-node-test3
kubectl delete deployments,services -l app=multi-node-test4
kubectl delete deployments,services -l app=multi-node-test5
# kubectl apply -f test-conductor-deployment.yaml
kubectl apply -f test-node-deployment1.yaml
kubectl apply -f test-node-service1.yaml
kubectl apply -f test-node-deployment2.yaml
kubectl apply -f test-node-service2.yaml
kubectl apply -f test-node-deployment3.yaml
kubectl apply -f test-node-service3.yaml
kubectl apply -f test-node-deployment4.yaml
kubectl apply -f test-node-service4.yaml
kubectl apply -f test-node-deployment5.yaml
kubectl apply -f test-node-service5.yaml
# kubectl apply -f test-conductor-service.yaml

kubectl wait --for=condition=Ready pods --all
