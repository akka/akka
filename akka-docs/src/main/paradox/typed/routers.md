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

The pool router is created with a routee `Behavior` and will spawn a number of children with that behavior which it will 
then forward messages to. 

If a child is stopped the pool router will remove it from its set of routees. When the last child stops the router itself will stop.
To make a resilient router that deals with failures the routee `Behavior` must be supervised.


Scala
:  @@snip [RouterSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/RouterSpec.scala) { #pool }

Java
:  @@snip [RouterTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/RouterTest.java) { #pool }


## Group Router

The group router is created with a `ServiceKey` and uses the receptionist (see @ref:[Receptionist](actor-discovery.md#Receptionist)) to discover
available actors for that key and routes messages to one of the currently known registered actors for a key.

FIXME: sample


## Routing strategies

There are two different strategies for selecting what routee a message should be forwarded to:

### Round Robin

Rotates over the set of routees making sure that if there are `n` routees, then for `n` messages
sent through the router, each actor get one messages.

For a group the fairness when the set of routees are changing is best effort.

### Random
Whenever is sent through the router, the message is forwarded to a routee that is randomly selected


## Routers and performance

Note that the routees are still scheduled on the ActorSystem dispatcher meaning that if they are CPU bound, there is no
gain from creating a router with more routees than there are available threads in the dispatcher.

Since the router itself is an actor and has a mailbox this means messages routed sequentially, there can be cases where
this is better to be avoided. Akka Typed does not provide an optimized tool for this.