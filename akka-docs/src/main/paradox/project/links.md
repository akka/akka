# Project

## Commercial Support

Commercial support is provided by [Lightbend](http://www.lightbend.com).
Akka is part of the [Lightbend Platform](http://www.lightbend.com/platform).

## Sponsors

**Lightbend** is the company behind the Akka Project, Scala Programming Language,
Play Web Framework, Lagom, sbt and many other open source projects. 
It also provides the Lightbend Reactive Platform, which is powered by an open source core and commercial Enterprise Suite for building scalable Reactive systems on the JVM. Learn more at [lightbend.com](http://www.lightbend.com).

## Mailing List

[Akka User Google Group](http://groups.google.com/group/akka-user)

## Gitter

Chat room about *using* Akka: [![gitter: akka/akka](https://img.shields.io/badge/gitter%3A-akka%2Fakka-blue.svg?style=flat-square)](https://gitter.im/akka/akka)

A chat room is available for all questions related to developing and contributing to Akka: [![gitter: akka/dev](https://img.shields.io/badge/gitter%3A-akka%2Fdev-blue.svg?style=flat-square)](https://gitter.im/akka/dev)


## Source Code

Akka uses Git and is hosted at [Github akka/akka](https://github.com/akka/akka).

## Releases Repository

All Akka releases are published via Sonatype to Maven Central, see
[search.maven.org](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.typesafe.akka%22)

## Snapshots Repository

Nightly builds are available in [https://repo.akka.io/snapshots](https://repo.akka.io/snapshots/) as both `SNAPSHOT` and
timestamped versions.

For timestamped versions, pick a timestamp from
[https://repo.akka.io/snapshots/com/typesafe/akka](https://repo.akka.io/snapshots/com/typesafe/akka).
All Akka modules that belong to the same build have the same timestamp.

@@@ warning

The use of Akka SNAPSHOTs, nightlies and milestone releases is discouraged unless you know what you are doing.

@@@

### sbt definition of snapshot repository

Make sure that you add the repository to the sbt resolvers:

```
resolvers += "Akka Snapshots" at "https://repo.akka.io/snapshots/"
```

Define the library dependencies with the timestamp as version. For example:

@@@vars
```
libraryDependencies += "com.typesafe.akka" % "akka-remote_$scala.binary_version$" % "2.5-20170510-230859"
```
@@@

### Maven definition of snapshot repository

Make sure that you add the repository to the Maven repositories in pom.xml:

```
<repositories>
  <repository>
    <id>akka-snapshots</id>
    <name>Akka Snapshots</name>
    <url>https://repo.akka.io/snapshots/</url>
    <layout>default</layout>
  </repository>
</repositories>
```

Define the library dependencies with the timestamp as version. For example:

@@@vars
```
<dependencies>
  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-remote_$scala.binary_version$</artifactId>
    <version>2.5-20170510-230859</version>
  </dependency>
</dependencies>
```
@@@
