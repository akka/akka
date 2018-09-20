# Cluster Singleton

## Dependency

To use Cluster Singleton, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.

@@@

For some use cases it is convenient and sometimes also mandatory to ensure that
you have exactly one actor of a certain type running somewhere in the cluster.

Some examples:

 * single point of responsibility for certain cluster-wide consistent decisions, or
coordination of actions across the cluster system
 * single entry point to an external system
 * single master, many workers
 * centralized naming service, or routing logic

Using a singleton should not be the first design choice. It has several drawbacks,
such as single-point of bottleneck. Single-point of failure is also a relevant concern,
but for some cases this feature takes care of that by making sure that another singleton
instance will eventually be started.

## Example

Any `Behavior` can be run as a singleton. E.g. a basic counter:

Scala
:  @@snip [SingletonCompileOnlySpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/SingletonCompileOnlySpec.scala) { #counter }

Java
:  @@snip [SingletonCompileOnlyTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/SingletonCompileOnlyTest.java) { #counter }

Then on every node in the cluster, or every node with a given role, use the `ClusterSingleton` extension
to spawn the singleton. An instance will per data centre of the cluster:


Scala
:  @@snip [SingletonCompileOnlySpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/SingletonCompileOnlySpec.scala) { #singleton }

Java
:  @@snip [SingletonCompileOnlyTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/SingletonCompileOnlyTest.java) { #import #singleton }

## Accessing singleton of another data centre

TODO

