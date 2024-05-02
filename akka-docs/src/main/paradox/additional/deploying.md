---
project.description: How to deploy Akka Cluster to Kubernetes and Docker.
---
# Deploying

## Deploying to Kubernetes

You can deploy to Kubernetes according to the guide and example project for @extref:[Deploying Akka Cluster to Kubernetes](akka-management:kubernetes-deployment/index.html).

### Cluster bootstrap

To take advantage of running inside Kubernetes while forming a cluster,
@extref:[Akka Cluster Bootstrap](akka-management:bootstrap/) helps forming or joining a cluster using Akka Discovery to discover peer nodes. 
with the Kubernetes API or Kubernetes via DNS.  

### Rolling updates

Enable the @extref:[Kubernetes Rolling Updates](akka-management:rolling-updates.html#kubernetes-rolling-updates)
and @extref:[app-version from Deployment](akka-management:rolling-updates.html#app-version-from-deployment)
features from Akka Management for smooth rolling updates.
 
### Resource limits

To avoid CFS scheduler limits, it is best not to use `resources.limits.cpu` limits, but use `resources.requests.cpu` configuration instead.

## Deploying to Docker containers

You can use both Akka remoting and Akka Cluster inside Docker containers. Note
that you will need to take special care with the network configuration when using Docker,
described here: @ref:[Akka behind NAT or in a Docker container](../remoting-artery.md#remote-configuration-nat-artery)

For the JVM to run well in a Docker container, there are some general (not Akka specific) parameters that might need tuning:

### Resource constraints

Docker allows [constraining each containers' resource usage](https://docs.docker.com/config/containers/resource_constraints/).

#### Memory

You may want to look into using `-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap` options for your JVM later than 8u131, which makes it understand c-group memory limits. On JVM 10 and later, the `-XX:+UnlockExperimentalVMOptions` option is no longer needed.

#### CPU

For multithreaded applications such as the JVM, the CFS scheduler limits are an ill fit, because they will restrict
the allowed CPU usage even when more CPU cycles are available from the host system. This means your application may be
starved of CPU time, but your system appears idle.

For this reason, it is best to avoid `--cpus` and `--cpu-quota` entirely, and instead specify relative container weights using `--cpu-shares` instead.

