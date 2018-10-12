# Cluster Sharding

## Dependency

To use Akka Cluster Sharding Typed, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-sharding-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

For an introduction to Sharding concepts see @ref:[Cluster Sharding](../cluster-sharding.md). This documentation shows how to use the typed
Cluster Sharding API.

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.

@@@

## Basic example

Sharding is accessed via the `ClusterSharding` extension

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #sharding-extension }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #import #sharding-extension }

It is common for sharding to be used with persistence however any Behavior can be used with sharding e.g. a basic counter:

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #counter-messages #counter }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #counter-messages #counter }

Each Entity type has a key that is then used to retrieve an EntityRef for a given entity identifier.

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #start }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #start }

Messages to a specific entity are then sent via an EntityRef.
It is also possible to wrap methods in a `ShardingEnvelop` or define extractor functions and send messages directly to the shard region.

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #send }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #send }

## Persistence example

When using sharding entities can be moved to different nodes in the cluster. Persistence can be used to recover the state of
an actor after it has moved.

Taking the larger example from the @ref:[persistence documentation](persistence.md#larger-example) and making it into
a sharded entity is the same as for a non persistent behavior. The behavior:

Scala
:  @@snip [BlogPostExample.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BlogPostExample.scala) { #behavior }

Java
:  @@snip [InDepthPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #behavior }

To create the entity:

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #persistence }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #persistence }

Sending messages to entities is the same as the example above. The only difference is when an entity is moved the state will be restored.
See @ref:[persistence](persistence.md) for more details.

## Passivation

If the state of the entities are persistent you may stop entities that are not used to
reduce memory consumption. This is done by the application specific implementation of
the entity actors for example by defining receive timeout (`context.setReceiveTimeout`).
If a message is already enqueued to the entity when it stops itself the enqueued message
in the mailbox will be dropped. To support graceful passivation without losing such
messages the entity actor can send `ClusterSharding.Passivate` to to the
@scala:[`ActorRef[ShardCommand]`]@java:[`ActorRef<ShardCommand>`] that was passed in to
the factory method when creating the entity. The specified `handOffStopMessage` message
will be sent back to the entity, which is then supposed to stop itself. Incoming messages
will be buffered by the `Shard` between reception of `Passivate` and termination of the
entity. Such buffered messages are thereafter delivered to a new incarnation of the entity.

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #counter-messages #counter-passivate }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #counter-messages #counter-passivate #counter-passivate-start }

