# Project

## Commercial Support

Commercial support is provided by [Lightbend](https://www.lightbend.com).
Akka is part of the [Akka Platform](https://www.lightbend.com/akka-platform).

## Sponsors

**Lightbend** is the company behind the Akka Project, Scala Programming Language,
Play Web Framework, Lagom, sbt and many other open source projects. 
It also provides the Lightbend Reactive Platform, which is powered by an open source core and commercial Enterprise Suite for building scalable Reactive systems on the JVM. Learn more at [lightbend.com](https://www.lightbend.com).

## Akka Discuss Forums

[Akka Discuss Forums](https://discuss.akka.io)

## Gitter

Chat room about *using* Akka: [![gitter: akka/akka](https://img.shields.io/badge/gitter%3A-akka%2Fakka-blue.svg?style=flat-square)](https://gitter.im/akka/akka)

A chat room is available for all questions related to developing and contributing to Akka: [![gitter: akka/dev](https://img.shields.io/badge/gitter%3A-akka%2Fdev-blue.svg?style=flat-square)](https://gitter.im/akka/dev)

## Source Code

Akka uses Git and is hosted at [Github akka/akka](https://github.com/akka/akka).

## Releases Repository

All Akka releases are published via Sonatype to Maven Central, see
[search.maven.org](https://search.maven.org/search?q=g:com.typesafe.akka)

## Snapshots Repository

Snapshot builds are available at [https://oss.sonatype.org/content/repositories/snapshots/com/typesafe/akka/](https://oss.sonatype.org/content/repositories/snapshots/com/typesafe/akka/). All Akka modules that belong to the same build have the same version.

@@@ warning

The use of Akka SNAPSHOTs, nightlies and milestone releases is discouraged unless you know what you are doing.

@@@

### sbt definition of snapshot repository

Make sure that you add the repository to the sbt resolvers:

```
resolvers ++= Resolver.sonatypeOssRepos("snapshots")
```

Define the library dependencies with the complete version. For example:

@@@vars
```
libraryDependencies += "com.typesafe.akka" % "akka-remote_$scala.binary.version$" % "2.6.14+72-53943d99-SNAPSHOT"
```
@@@

### Maven definition of snapshot repository

Make sure that you add the repository to the Maven repositories in pom.xml:

```
<repositories>
  <repository>
    <id>oss-sonatype</id>
    <url>https://oss.sonatype.org/content/repositories/snapshots</url>
    <layout>default</layout>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>
```

Define the library dependencies with the timestamp as version. For example:

@@@vars
```
<dependencies>
  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-remote_$scala.binary.version$</artifactId>
    <version>2.6.14+72-53943d99-SNAPSHOT</version>
  </dependency>
</dependencies>
```
@@@
