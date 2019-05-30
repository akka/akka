# Routers

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

The pool router is created with a routee `Behavior` factory and spawns a number of children with that behavior which it will 
then forward messages to.

If a child is stopped the pool router removes it from its set of routees. When the last child stops the router itself stops.
To make a resilient router that deals with failures the routee `Behavior` must be supervised.

Note that it is important that the factory returns a new behavior instance for every call to the factory or else
routees may end up sharing mutable state and not work as expected.

Scala
:  @@snip [RouterSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/RouterSpec.scala) { #pool }

Java
:  @@snip [RouterTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/RouterTest.java) { #pool }


## Group Router

The group router is created with a `ServiceKey` and uses the receptionist (see @ref:[Receptionist](actor-discovery.md#Receptionist)) to discover
available actors for that key and routes messages to one of the currently known registered actors for a key.

Since the receptionist is used this means the group router is cluster aware out of the box and will pick up routees
registered on any node in the cluster (there is currently no logic to avoid routing to unreachable nodes, see [#26355](https://github.com/akka/akka/issues/26355)).

It also means that the set of routees is eventually consistent, and that immediately when the group router is started
the set of routees it knows about is empty. When the set of routees is empty messages sent to the router is forwarded
to dead letters.

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

This is the default for pool routers.

### Random
Randomly selects a routee when a message is sent through the router.

This is the default for group routers.

## Routers and performance

Note that if the routees are sharing a resource, the resource will determine if increasing the number of
actors will actually give higher throughput or faster answers. For example if the routees are CPU bound actors
it will not give better performance to create more routees than there are threads to execute the actors. 

Since the router itself is an actor and has a mailbox this means that messages are routed sequentially to the routees
where it can be processed in parallel (depending on the available threads in the dispatcher).
In a high throughput use cases the sequential routing could be a bottle neck. Akka Typed does not provide an optimized tool for this.