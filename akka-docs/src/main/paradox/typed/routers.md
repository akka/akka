# Routers

@@@ note
For the Akka Classic documentation of this feature see @ref:[Classic Routing](../routing.md).
@@@

## Dependency

To use Akka Actor Typed, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-actor-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

In some cases it is useful to distribute messages of the same type over a set of actors, so that messages can be 
processed in parallel - a single actor will only process one message at a time. 

The router itself is a behavior that is spawned into a running actor that will then forward any message sent to it
to one final recipient out of the set of routees.

There are two kinds of routers included in Akka Typed - the pool router and the group router. 

## Pool Router

The pool router is created with a routee `Behavior` and spawns a number of children with that behavior which it will 
then forward messages to.

If a child is stopped the pool router removes it from its set of routees. When the last child stops the router itself stops.
To make a resilient router that deals with failures the routee `Behavior` must be supervised.

Scala
:  @@snip [RouterSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/RouterSpec.scala) { #pool }

Java
:  @@snip [RouterTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/RouterTest.java) { #pool }


## Group Router

The group router is created with a `ServiceKey` and uses the receptionist (see @ref:[Receptionist](actor-discovery.md#receptionist)) to discover
available actors for that key and routes messages to one of the currently known registered actors for a key.

Since the receptionist is used this means the group router is cluster-aware out of the box. The router sends
messages to registered actors on any node in the cluster that is reachable. If no reachable actor exists the router
will fallback and route messages to actors on nodes marked as unreachable.

That the receptionist is used also means that the set of routees is eventually consistent, and that immediately when 
the group router is started the set of routees it knows about is empty, until it has seen a listing from the receptionist
it stashes incoming messages and forwards them as soon as it gets a listing from the receptionist.  

When the router has received a listing from the receptionist and the set of registered actors is empty the router will
drop them (publish them to the event stream as `akka.actor.Dropped`).

Scala
:  @@snip [RouterSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/RouterSpec.scala) { #group }

Java
:  @@snip [RouterTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/RouterTest.java) { #group }


## Routing strategies

There are two different strategies for selecting what routee a message is forwarded to that can be selected
from the router before spawning it:

Scala
:  @@snip [RouterSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/RouterSpec.scala) { #strategy }

Java
:  @@snip [RouterTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/RouterTest.java) { #strategy } 

### Round Robin

Rotates over the set of routees making sure that if there are `n` routees, then for `n` messages
sent through the router, each actor is forwarded one message.

Round robin gives fair routing where every available routee gets the same amount of messages as long as the set
of routees stays relatively stable, but may be unfair if the set of routees changes a lot.

This is the default for pool routers as the pool of routees is expected to remain the same. 

### Random
Randomly selects a routee when a message is sent through the router.

This is the default for group routers as the group of routees is expected to change as nodes join and leave the cluster.

## Routers and performance

Note that if the routees are sharing a resource, the resource will determine if increasing the number of
actors will actually give higher throughput or faster answers. For example if the routees are CPU bound actors
it will not give better performance to create more routees than there are threads to execute the actors. 

Since the router itself is an actor and has a mailbox this means that messages are routed sequentially to the routees
where it can be processed in parallel (depending on the available threads in the dispatcher).
In a high throughput use cases the sequential routing could become a bottle neck. Akka Typed does not provide an optimized tool for this.
