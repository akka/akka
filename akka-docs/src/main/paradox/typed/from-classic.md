# Learning Akka Typed from Classic

This is a good starting point if you already know the classic actor APIs and would like to learn Akka Typed.
Many concepts are the same and this guide tries to highlight differences and how to do certain things
in Typed compared to classic.

Akka Classic is the original Actor APIs, which have been improved by more type safe and guided Actor APIs,
known as Akka Typed. Akka Classic is still fully supported and existing applications can continue to use
the classic APIs. It is also possible to use Akka Typed together with classic actors within the same
ActorSystem, see @ref[coexistence](coexisting.md). For new projects we recommend using the new Actor APIs.

## Dependencies

The dependencies of the Typed modules are named by adding `-typed` suffix of the corresponding classic
module, with a few exceptions.

For example `akka-cluster-typed`:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-typed_$scala.binary_version$
  version=$akka.version$
}

Artifact names:

| Classic               | Typed                       |
|-----------------------|-----------------------------|
| akka-actor            | akka-actor-typed            |
| akka-cluster          | akka-cluster-typed          |
| akka-cluster-sharding | akka-cluster-sharding-typed |
| akka-cluster-tools    | akka-cluster-typed          |
| akka-distributed-data | akka-cluster-typed          |
| akka-persistence      | akka-persistence-typed      |
| akka-stream           | akka-stream-typed           |
| akka-testkit          | akka-actor-testkit-typed    |

Cluster Singleton and Distributed Data are included in `akka-cluster-typed`.

Artifacts not listed in above table don't have a specific API for Akka Typed.

## Package names

The convention of the package names in Akka Typed is to add `typed.scaladsl` and `typed.javadsl` to the
corresponding Akka classic package name. `scaladsl` and `javadsl` is the convention to separate Scala and Java
APIs, which is familiar from Akka Streams.

Examples of a few package names:

| Classic          | Typed for Scala                 | Typed for Java                 |
|------------------|---------------------------------|--------------------------------|
| akka.actor       | akka.actor.typed.scaladsl       | akka.actor.typed.javadsl       |
| akka.cluster     | akka.cluster.typed.scaladsl     | akka.cluster.typed.javadsl     |
| akka.persistence | akka.persistence.typed.scaladsl | akka.persistence.typed.javadsl |

## Actor definition

A classic actor is defined by a class extending @scala[`akka.actor.Actor`]@java[`akka.actor.AbstractActor`].

An actor in Typed is defined by a class extending @scala[`akka.actor.typed.scaladsl.AbstractBehavior`]@java[`akka.actor.typed.javadsl.AbstractBehavior`].

It's also possible to define an actor in Typed from functions instead of extending a class. This is called
the @ref:[functional style](style-guide.md#functional-versus-object-oriented-style).

TODO snip

Why is it called `Behavior` and not `Actor`?

In Typed the `Behavior` defines how to handle incoming messages and after processing each message it may return
a different `Behavior` to be used for the next message. This means that an actor is started with an initial `Behavior`
and may change `Behavior` over it's lifecycle. This is described more in the section about @ref:[become](#become).

Note that the `Behavior` has a type parameter describing the type of messages that it can handle. This information
is not defined explicitly for a classic actor.

Links to reference documentation:

* @ref:[Classic](../actors.md#defining-an-actor-class)
* @ref:[Typed](actors.md#first-example)

## actorOf and Props

A classic actor is started with the `actorOf` method of the `ActorContext` or `ActorSystem`.

Corresponding method in Typed is called `spawn` in the @scala[`akka.actor.typed.scaladsl.ActorContext`]@java[`akka.actor.typed.javadsl.ActorContext`].

There is no `spawn` in the @scala[`akka.actor.typed.scaladsl.ActorSystem`]@java[`akka.actor.typed.javadsl.ActorSystem`]
for creating top level actors. Instead, the single to level actor is defined by a user guardian `Behavior` that
is given when starting the `ActorSystem`. Other actors are started as children of that user guardian actor or
children of other actors in the actor hierarchy. This is explained more in @ref:[ActorSystem](#actorsystem).

`actorOf` takes an `akka.actor.Props` parameter, which is like a factory for creating the actor instance, and it's
also used when creating a new instance when the actor is restarted. The `Props` may also define additional
properties such as which dispatcher to use for the actor.

The `spawn` method in Typed creates an actor from a given `Behavior` instead of via a `Props` factory.
The factory aspect is instead defined via `Behaviors.setup` when using the object-oriented style with
a class extending `AbstractBehavior`. For the function style there is typically no need for the factory.

Additional properties such as which dispatcher to use for the actor can still be given via an optional
`akka.actor.typed.Props` parameter of the `spawn` method.

The `name` parameter of `actorOf` is optional and if not defined the actor will have a generated name. Corresponding
in Typed is achieved with the `spawnAnonymous` method.

Links to reference documentation:

* @ref:[Classic](../actors.md#creating-actors-with-props)
* @ref:[Typed](actor-lifecycle.md#creating-actors)

## ActorRef

`akka.actor.ActorRef` has its correspondence in `akka.actor.typed.ActorRef`. The difference being that the latter
has a type parameter describing which messages the actor can handle. This information is not defined for a
classic actor and you can send any type of message to a classic `ActorRef` even though the actor may not
understand it.

## ActorSystem

`akka.actor.ActorSystem` has its correspondence in `akka.actor.typed.ActorSystem`. One difference is that
when creating an `ActorSystem` in Typed you give it a `Behavior` that will be used as the top level user guardian.

It's from the user guardian you create additional actors for the application and initialize tools like
Cluster Sharding. In contrast, such initialization are typically performed from the "outside" after
starting a classic `ActorSystem`.

`actorOf` of the classic `ActorSystem` is typically used to create a few (or many) top level actors. The
`ActorSystem` in Typed doesn't have that capability. Instead, such actors are started as children of
the user guardian actor or children of other actors in the actor hierarchy.

## become

A classic actor can change its message processing behavior by using `become` in `ActorContext`. In Typed this
is done by returning a new `Behavior` after processing a message. The returned `Behavior` will be used for the
next received message.

There is no correspondence to `unbecome` in Typed. Instead you must explicitly keep track of and return the "previous"
`Behavior`.

Links to reference documentation:

* @ref:[Classic](../actors.md#actor-hotswap)

## sender

There is no @scala[`sender()`]@java[`getSender()`] in Typed. Instead you have to explicitly include an `ActorRef`
representing the sender—or rather representing where to send a reply to—in the messages.

The reason for not having an implicit sender in Typed is that it wouldn't be possible to know the type
for the sender @scala[`ActorRef[T]`]@java[`ActorRef<T>`] at compile time. It's also much better to define
this explicitly in the messages as it becomes more clear what the message protocol expects.

Links to reference documentation:

* @ref:[Classic](../actors.md#actors-tell-sender)
* @ref:[Typed](interaction-patterns.md#request-response)

## parent

There is no @scala[`parent`]@java[`getParent`] in Typed. Instead you have to explicitly include the `ActorRef`
of the parent as a parameter when constructing the `Behavior`.

The reason for not having a parent in Typed is that it wouldn't be possible to know the type
for the parent @scala[`ActorRef[T]`]@java[`ActorRef<T>`] at compile time without having an additional
type parameter in the `Behavior`. For testing purposes it's also better to pass in the `parent` since it
can be replaced by a probe or being stubbed out in tests.

## FSM

With classic actors there is explicit support for building Finite State Machines. No support is needed in
Akka Typed as it is straightforward to represent FSMs with behaviors.

Links to reference documentation:

* @ref:[Classic](../fsm.md)
* @ref:[Typed](fsm.md)

## TODO more topics


- ask vs context.ask
- actorSelection Identify vs Receptionist
- supervision
  - Backoff
  - default restart vs stop
  - decorator of Behavior, define when spawning or for the Behavior itself
- routers
- remote deployment
- context.child returning ActorRef[Nothing]
  can't send to it?
- ReceiveBuilder naming
- become vs returning next Behavior
- Logging
- self vs context.self
- lifcycle hooks vs signals
- watch vs watchWith
- Timers
- stopping
  - context.stop only for children
  - no PoisonPill
- Stash vs StashBuffer
- How to specify a dispatcher
- PersistentActor vs EventSourcedBehavior
- Asynchronous Testing
- Synchronous Testing
