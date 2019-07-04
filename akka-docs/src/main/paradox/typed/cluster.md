# Cluster

## Dependency

To use Akka Cluster Typed, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

For an introduction to Akka Cluster concepts see @ref:[Cluster Specification](../common/cluster.md). This documentation shows how to use the typed
Cluster API.

## Examples

All of the examples below assume the following imports:

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-imports }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-imports }

And the minimum configuration required is to set a host/port for remoting and the `akka.actor.provider = "cluster"`.

@@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #config-seeds }


## Cluster API extension

The typed Cluster extension gives access to management tasks (Joining, Leaving, Downing, …) and subscription of
cluster membership events (MemberUp, MemberRemoved, UnreachableMember, etc). Those are exposed as two different actor
references, i.e. it’s a message based API.

The references are on the `Cluster` extension:

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-create }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-create }

The Cluster extensions gives you access to:

* manager: An @scala[`ActorRef[ClusterCommand]`]@java[`ActorRef<ClusterCommand>`] where a `ClusterCommand` is a command such as: `Join`, `Leave` and `Down`
* subscriptions: An `ActorRef[ClusterStateSubscription]` where a `ClusterStateSubscription` is one of `GetCurrentState` or `Subscribe` and `Unsubscribe` to cluster events like `MemberRemoved`
* state: The current `CurrentClusterState`


### Cluster Management

If not using configuration to specify seeds joining the cluster can be done programmatically via the `manager`.

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-join }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-join }

Leaving and downing are similar e.g.

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-leave }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-leave }

### Cluster subscriptions

Cluster `subscriptions` can be used to receive messages when cluster state changes. For example, registering
for all `MemberEvent`s, then using the `manager` to have a node leave the cluster will result in events
for the node going through the lifecycle described in @ref:[Cluster Specification](../common/cluster.md).

This example subscribes with a `TestProbe` but in a real application it would be an Actor:

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-subscribe }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-subscribe }

Then asking a node to leave:

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-leave-example }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-leave-example }

## Serialization

See [serialization](https://doc.akka.io/docs/akka/current/scala/serialization.html) for how messages are sent between
ActorSystems. Actor references are typically included in the messages,
since there is no `sender`. To serialize actor references to/from string representation you will use the `ActorRefResolver`.
For example here's how a serializer could look for the `Ping` and `Pong` messages above:

Scala
:  @@snip [PingSerializer.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/PingSerializer.scala) { #serializer }

Java
:  @@snip [PingSerializerExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/PingSerializerExampleTest.java) { #serializer }
