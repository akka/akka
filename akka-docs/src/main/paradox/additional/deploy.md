# How can I deploy Akka?

Akka can be used in different ways:

 * As a library: used as a regular JAR on the classpath and/or in a web app, to
be put into `WEB-INF/lib`
 * As an application packaged with [sbt-native-packager](https://github.com/sbt/sbt-native-packager)
 
## Lightbend Enterprise Suite

Lightbend Enterprise Suite is a solution for managing Lightbend Reactive Platform applications across a cluster of machines.
It is reactive from the ground up thus enabling operations to provide the resiliency required to unleash the full benefits 
of the Lightbend Reactive Platform in their organization.

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
@java[@extref[Cluster with docker-compse example project](samples:akka-samples-cluster-docker-compose-java)]
@scala[@extref[Cluster with docker-compose example project](samples:akka-samples-cluster-docker-compose-scala)]
to see what this looks like in practice.

For the JVM to run well in a Docker container, there are some general (not Akka specific) parameters that might need tuning. You may want to look into using [`-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap`](https://dzone.com/articles/running-a-jvm-in-a-container-without-getting-kille) options for your JVM later than 8u131, which makes it understand c-group memory limits.



