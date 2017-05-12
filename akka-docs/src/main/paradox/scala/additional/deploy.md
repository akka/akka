# How can I deploy Akka?

Akka can be used in different ways:

 * As a library: used as a regular JAR on the classpath and/or in a web app, to
be put into `WEB-INF/lib`
 * As an application packaged with [sbt-native-packager](https://github.com/sbt/sbt-native-packager)
 * As an application packaged and deployed using [Lightbend ConductR](http://www.lightbend.com/products/conductr).

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

For an example of how to set up a project using Akka Cluster and Docker take a look at the
["akka-docker-cluster" sample](https://github.com/muuki88/activator-akka-docker).



