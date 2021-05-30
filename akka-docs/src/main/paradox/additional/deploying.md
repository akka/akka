---
project.description: How to deploy Akka Cluster to Kubernetes and Docker.
---
# Deploying

## Deploying to Kubernetes

[Akka Cloud Platform](https://developer.lightbend.com/docs/akka-platform-guide/deployment/index.html) is the easiest way to deploy an Akka Cluster application to Amazon Elastic Kubernetes Service (EKS) or Google Kubernetes Engine (GKE).

Alternatively, you can deploy to Kubernetes according to the guide and example project for [Deploying Akka Cluster to Kubernetes](https://doc.akka.io/docs/akka-management/current/kubernetes-deployment/index.html), but that requires more expertise of Kubernetes.

### Cluster bootstrap

To take advantage of running inside Kubernetes while forming a cluster, 
[Akka Cluster Bootstrap](https://doc.akka.io/docs/akka-management/current/bootstrap/) helps forming or joining a cluster using Akka Discovery to discover peer nodes. 
with the Kubernetes API or Kubernetes via DNS.  

You can look at the
@extref[Cluster with Kubernetes example project](samples:akka-sample-cluster-kubernetes-java)
to see what this looks like in practice.
 
### Resource limits

To avoid CFS scheduler limits, it is best not to use `resources.limits.cpu` limits, but use `resources.requests.cpu` configuration instead.

## Deploying to Docker containers

You can use both Akka remoting and Akka Cluster inside Docker containers. Note
that you will need to take special care with the network configuration when using Docker,
described here: @ref:[Akka behind NAT or in a Docker container](../remoting-artery.md#remote-configuration-nat-artery)

You can look at the
@java[@extref[Cluster with docker-compse example project](samples:akka-sample-cluster-docker-compose-java)]
@scala[@extref[Cluster with docker-compose example project](samples:akka-sample-cluster-docker-compose-scala)]
to see what this looks like in practice.

For the JVM to run well in a Docker container, there are some general (not Akka specific) parameters that might need tuning:

### Resource constraints

Docker allows [constraining each containers' resource usage](https://docs.docker.com/config/containers/resource_constraints/).

#### Memory

You may want to look into using [`-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap`](https://dzone.com/articles/running-a-jvm-in-a-container-without-getting-kille) options for your JVM later than 8u131, which makes it understand c-group memory limits. On JVM 10 and later, the `-XX:+UnlockExperimentalVMOptions` option is no longer needed.

#### CPU

For multi-threaded applications such as the JVM, the CFS scheduler limits are an ill fit, because they will restrict
the allowed CPU usage even when more CPU cycles are available from the host system. This means your application may be
starved of CPU time, but your system appears idle.

For this reason, it is best to avoid `--cpus` and `--cpu-quota` entirely, and instead specify relative container weights using `--cpu-shares` instead.

