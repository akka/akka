# TODO

These topics should be incorporated somewhere?

## Prerequisites

Akka requires that you have [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) or
later installed on your machine.

## Akka can be used in different ways

Akka is a toolkit, not a framework: you integrate it into your build like any other library
without having to follow a particular source code layout. When expressing your systems as collaborating
Actors you may feel pushed more towards proper encapsulation of internal state, you may find that
there is a natural separation between business logic and inter-component communication.

Akka applications are typically deployed as follows:

 * as a library: used as a regular JAR on the classpath or in a web app.
 * packaged with [sbt-native-packager](https://github.com/sbt/sbt-native-packager).
 * packaged and deployed using [Lightbend ConductR](http://www.lightbend.com/products/conductr).
 
## How can I use and deploy Akka?

Akka can be used in different ways:

 * As a library: used as a regular JAR on the classpath and/or in a web app, to
be put into `WEB-INF/lib`
 * As an application packaged with [sbt-native-packager](https://github.com/sbt/sbt-native-packager)
 * As an application packaged and deployed using [Lightbend ConductR](http://www.lightbend.com/products/conductr).

### Native Packager

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

### In a Docker container

You can use both Akka remoting and Akka Cluster inside of Docker containers. But note
that you will need to take special care with the network configuration when using Docker,
described here: @ref:[Akka behind NAT or in a Docker container](../../scala/remoting.md#remote-configuration-nat)

For an example of how to set up a project using Akka Cluster and Docker take a look at the
["akka-docker-cluster" sample](https://github.com/muuki88/activator-akka-docker).

## Commercial Support

Akka is available from Lightbend Inc. under a commercial license which includes
development or production support, read more [here](http://www.lightbend.com/how/subscription).

## Using a snapshot version

The Akka nightly snapshots are published to [http://repo.akka.io/snapshots](http://repo.akka.io/snapshots)/ and are
versioned with both `SNAPSHOT` and timestamps. You can choose a timestamped
version to work with and can decide when to update to a newer version.

@@@ warning

The use of Akka SNAPSHOTs, nightlies and milestone releases is discouraged unless you know what you are doing.

@@@

<a id="build-tool"></a>
## Using a build tool

Akka can be used with build tools that support Maven repositories.

## Using Akka with Maven

The simplest way to get started with Akka and Maven is to download the ready to run sample
named [Akka Main in Java](@exampleCodeService@/akka-samples-main-java).

Since Akka is published to Maven Central (for versions since 2.1-M2), it is
enough to add the Akka dependencies to the POM. For example, here is the
dependency for akka-actor:

```xml
<dependency>
  <groupId>com.typesafe.akka</groupId>
  <artifactId>akka-actor_@binVersion@</artifactId>
  <version>@version@</version>
</dependency>
```

For snapshot versions, the snapshot repository needs to be added as well:

```xml
<repositories>
  <repository>
    <id>akka-snapshots</id>
      <snapshots>
        <enabled>true</enabled>
      </snapshots>
    <url>http://repo.akka.io/snapshots/</url>
  </repository>
</repositories>
```

**Note**: for snapshot versions both `SNAPSHOT` and timestamped versions are published.

## Using Akka with SBT

The simplest way to get started with Akka and SBT is to use a [Gitter8](http://www.foundweekends.org/giter8/) template
named [Hello Akka!](https://github.com/akka/hello-akka.g8). If you have *sbt* already installed, you can create a project
from this template by running:

```
sbt new akka/hello-akka.g8
```

Summary of the essential parts for using Akka with SBT:

SBT installation instructions on [http://www.scala-sbt.org/release/tutorial/Setup.html](http://www.scala-sbt.org/release/tutorial/Setup.html)

`build.sbt` file:

```
name := "My Project"

version := "1.0"

scalaVersion := "@scalaVersion@"

libraryDependencies +=
  "com.typesafe.akka" %% "akka-actor" % "@version@" @crossString@```

**Note**: the libraryDependencies setting above is specific to SBT v0.12.x and higher.  If you are using an older version of SBT, the libraryDependencies should look like this:

```
libraryDependencies +=
  "com.typesafe.akka" % "
@ref:[akka-actor](../general/configuration.md#akka-actor)
@binVersion
@" % "@version@"```

For snapshot versions, the snapshot repository needs to be added as well:

```
resolvers += "Akka Snapshot Repository" at "
[http://repo.akka.io/snapshots](http://repo.akka.io/snapshots)
/"```

## Using Akka with Gradle

Requires at least [Gradle](https://gradle.org) 1.4
Uses the [Scala plugin](http://www.gradle.org/docs/current/userguide/scala_plugin.html)

```
apply plugin: 'scala'

repositories {
  mavenCentral()
}

dependencies {
  compile 'org.scala-lang:scala-library:@
[scalaVersion@](mailto:scalaVersion@)
'
}

tasks.withType(ScalaCompile) {
  scalaCompileOptions.useAnt = false
}

dependencies {
  compile group: 'com.typesafe.akka', name: '
@ref:[akka-actor](../general/configuration.md#akka-actor)
@
[binVersion@](mailto:binVersion@)
', version: '
[@version](mailto:@version)
@
'
  compile group: 'org.scala-lang', name: 'scala-library', version: '
[@scalaVersion](mailto:@scalaVersion)
@
'
}```

For snapshot versions, the snapshot repository needs to be added as well:

```
repositories {
  mavenCentral()
  maven {
    url "
[http://repo.akka.io/snapshots](http://repo.akka.io/snapshots)
/"
  }
}```

## Using Akka with Eclipse

Setup SBT project and then use [sbteclipse](https://github.com/typesafehub/sbteclipse) to generate an Eclipse project.

## Using Akka with IntelliJ IDEA

Setup SBT project and then use [sbt-idea](https://github.com/mpeltonen/sbt-idea) to generate an IntelliJ IDEA project.

## Using Akka with NetBeans

Setup SBT project and then use [nbsbt](https://github.com/dcaoyuan/nbsbt) to generate a NetBeans project.

You should also use [nbscala](https://github.com/dcaoyuan/nbscala) for general scala support in the IDE.

## Do not use -optimize Scala compiler flag

@@@ warning

Akka has not been compiled or tested with -optimize Scala compiler flag.
Strange behavior has been reported by users that have tried it.

@@@

## Build from sources

Akka uses Git and is hosted at [Github](https://github.com).
 
Continue reading the [CONTRIBUTING](https://github.com/akka/akka/blob/master/CONTRIBUTING.md)

## Need help?

If you have questions you can get help on the [Akka Mailing List](https://groups.google.com/group/akka-user).

You can also ask for [commercial support](https://www.lightbend.com).

Thanks for being a part of the Akka community.