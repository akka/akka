# How can I deploy Akka?

Akka can be used in different ways:

 * As a library: used as a regular JAR on the classpath and/or in a web app, to
be put into `WEB-INF/lib`
 * As an application packaged with [sbt-native-packager](https://github.com/sbt/sbt-native-packager)
 
## Native Packager

[sbt-native-packager](https://github.com/sbt/sbt-native-packager) is a tool for creating
distributions of any type of application, including Akka applications.

Define sbt version in `project/build.properties` file:

```none
sbt.version=0.13.13
```

Add [sbt-native-packager](https://github.com/sbt/sbt-native-packager) in `project/plugins.sbt` file:

```none
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.5")
```

Follow the instructions for the `JavaAppPackaging` in the [sbt-native-packager plugin documentation](http://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_app/index.html).

## In a Docker container

You can use both Akka remoting and Akka Cluster inside of Docker containers. But note
that you will need to take special care with the network configuration when using Docker,
described here: @ref:[Akka behind NAT or in a Docker container](../remoting.md#remote-configuration-nat)

You can look at the
@java[@extref[Cluster with docker-compse example project](samples:akka-sample-cluster-docker-compose-java)]
@scala[@extref[Cluster with docker-compose example project](samples:akka-sample-cluster-docker-compose-scala)]
to see what this looks like in practice.

For the JVM to run well in a Docker container, there are some general (not Akka specific) parameters that might need tuning:

### Resource limits

Docker allows [constraining each containers' resource usage](https://docs.docker.com/config/containers/resource_constraints/).

#### Memory

You may want to look into using [`-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap`](https://dzone.com/articles/running-a-jvm-in-a-container-without-getting-kille) options for your JVM later than 8u131, which makes it understand c-group memory limits. On JVM 10 and later, the `-XX:+UnlockExperimentalVMOptions` option is no longer needed.

#### CPU

For multi-threaded applications such as the JVM, the CFS scheduler limits are an ill fit, because they will restrict
the allowed CPU usage even when more CPU cycles are available from the host system. This means your application may be
starved of CPU time, but your system appears idle.

For this reason, it is best to avoid `--cpus` and `--cpu-quota` entirely, and instead specify relative container weights using `--cpu-shares` instead.

## In Kubernetes

### Cluster bootstrap

To take advantage of the fact that your are running inside of Kubernetes while forming a cluster, you can use the [Akka Cluster Bootstrap](https://developer.lightbend.com/docs/akka-management/current/bootstrap/) module.

You can look at the
@extref[Cluster with Kubernetes example project](samples:akka-sample-cluster-kubernetes-java)
to see what this looks like in practice.

### Resource limits

To avoid CFS scheduler limits, it is best not to use `resources.limits.cpu` limits, but use `resources.requests.cpu` configuration instead.
