# Cluster Singleton

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

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.

@@@

## Dependency

To use Akka Cluster Singleton Typed, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-typed_2.12
  version=$akka.version$
}

## Example

Any `Behavior` can be run as a singleton. E.g. a basic counter:

Scala
:  @@snip [ShardingCompileOnlySpec.scala]($akka$/akka-cluster-sharding-typed/src/test/scala/doc/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #sharding-extension }

Java
:  @@snip [ShardingCompileOnlyTest.java]($akka$/akka-cluster-sharding-typed/src/test/java/jdoc/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #sharding-extension }

Then on every node in the cluster, or every node with a given role, use the `ClusterSingleton` extension
to spawn the singleton. Only a single instance will run in the cluster:


Scala
:  @@snip [ShardingCompileOnlySpec.scala]($akka$/akka-cluster-sharding-typed/src/test/scala/doc/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #singleton }

Java
:  @@snip [ShardingCompileOnlyTest.java]($akka$/akka-cluster-sharding-typed/src/test/java/jdoc/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #singleton }

