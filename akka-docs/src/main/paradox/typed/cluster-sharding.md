# Cluster Sharding

For an introduction to Sharding concepts see @ref:[Cluster Sharding](../cluster-sharding.md). This documentation shows how to use the typed
Cluster Sharding API.

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.

@@@

## Dependency

To use Akka Cluster Sharding Typed, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-sharding-typed_2.12
  version=$akka.version$
}

## Basic example

Sharding is accessed via the `ClusterSharding` extension

Scala
:  @@snip [ShardingCompileOnlySpec.scala]($akka$/akka-cluster-sharding-typed/src/test/scala/doc/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #sharding-extension }

Java
:  @@snip [ShardingCompileOnlyTest.java]($akka$/akka-cluster-sharding-typed/src/test/java/jdoc/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #import #sharding-extension }

It is common for sharding to be used with persistence however any Behavior can be used with sharding e.g. a basic counter:

Scala
:  @@snip [ShardingCompileOnlySpec.scala]($akka$/akka-cluster-sharding-typed/src/test/scala/doc/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #counter }

Java
:  @@snip [ShardingCompileOnlyTest.java]($akka$/akka-cluster-sharding-typed/src/test/java/jdoc/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #counter }

Each Entity type has a key that is then used to retrieve an EntityRef for a given entity identifier.

Scala
:  @@snip [ShardingCompileOnlySpec.scala]($akka$/akka-cluster-sharding-typed/src/test/scala/doc/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #spawn }

Java
:  @@snip [ShardingCompileOnlyTest.java]($akka$/akka-cluster-sharding-typed/src/test/java/jdoc/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #spawn }

Messages to a specific entity are then sent via an EntityRef.
It is also possible to wrap methods in a `ShardingEnvelop` or define extractor functions and send messages directly to the shard region.

Scala
:  @@snip [ShardingCompileOnlySpec.scala]($akka$/akka-cluster-sharding-typed/src/test/scala/doc/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #send }

Java
:  @@snip [ShardingCompileOnlyTest.java]($akka$/akka-cluster-sharding-typed/src/test/java/jdoc/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #send }

## Persistence example

When using sharding entities can be moved to different nodes in the cluster. Persistence can be used to recover the state of
an actor after it has moved. Currently Akka typed only has a Scala API for persistence, you can track the progress of the
Java API [here](https://github.com/akka/akka/issues/24193).

Taking the larger example from the @ref:[persistence documentation](persistence.md#larger-example) and making it into
a sharded entity is the same as for a non persistent behavior. The behavior:

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #behavior }

To create the entity:

Scala
:  @@snip [ShardingCompileOnlySpec.scala]($akka$/akka-cluster-sharding-typed/src/test/scala/doc/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #persistence }

Sending messages to entities is the same as the example above. The only difference is when an entity is moved the state will be restored.
See @ref:[persistence](persistence.md) for more details.
