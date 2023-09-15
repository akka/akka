# Learning Akka Typed from Classic

Akka Classic is the original Actor APIs, which have been improved by more type safe and guided Actor APIs,
known as Akka Typed.

If you already know the classic Actor APIs and would like to learn Akka Typed, this reference is a good resource.
Many concepts are the same and this page tries to highlight differences and how to do certain things
in Typed compared to classic.

You should probably learn some of the basics of Akka Typed to see how it looks like before diving into
the differences and details described here. A good starting point for that is the
@ref:[IoT example](guide/tutorial_3.md) in the Getting Started Guide or the examples shown in
@ref:[Introduction to Actors](actors.md).

Another good resource to learning Akka Typed is Manuel Bernhardt's [Tour of Akka Typed](https://manuel.bernhardt.io/articles/#akka-typed).

Note that Akka Classic is still fully supported and existing applications can continue to use
the classic APIs. It is also possible to use Akka Typed together with classic actors within the same
ActorSystem, see @ref[coexistence](coexisting.md). For new projects we recommend using the new Actor APIs.

## Dependencies

The dependencies of the Typed modules are named by adding `-typed` suffix of the corresponding classic
module, with a few exceptions.

For example `akka-cluster-typed`:

@@dependency[sbt,Maven,Gradle] {
  bomGroup=com.typesafe.akka bomArtifact=akka-bom_$scala.binary.version$ bomVersionSymbols=AkkaVersion
  symbol1=AkkaVersion
  value1="$akka.version$"
  group=com.typesafe.akka
  artifact=akka-cluster-typed_$scala.binary.version$
  version=AkkaVersion
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

| Classic               | Typed for Scala                       | Typed for Java                       |
|-----------------------|---------------------------------------|--------------------------------------|
| akka.actor            | akka.actor.typed.scaladsl             | akka.actor.typed.javadsl             |
| akka.cluster          | akka.cluster.typed                    | akka.cluster.typed                   |
| akka.cluster.sharding | akka.cluster.sharding.typed.scaladsl  | akka.cluster.sharding.typed.javadsl  |
| akka.persistence      | akka.persistence.typed.scaladsl       | akka.persistence.typed.javadsl       |

## Actor definition

A classic actor is defined by a class extending @scala[`akka.actor.Actor`]@java[`akka.actor.AbstractActor`].

An actor in Typed is defined by a class extending @scala[`akka.actor.typed.scaladsl.AbstractBehavior`]@java[`akka.actor.typed.javadsl.AbstractBehavior`].

It's also possible to define an actor in Typed from functions instead of extending a class. This is called
the @ref:[functional style](style-guide.md#functional-versus-object-oriented-style).

Classic HelloWorld actor:

Scala
:  @@snip [IntroSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/fromclassic/ClassicSample.scala) { #hello-world-actor }

Java
:  @@snip [IntroSpec.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/fromclassic/ClassicSample.java) { #hello-world-actor }

Typed HelloWorld actor:

Scala
:  @@snip [IntroSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/fromclassic/TypedSample.scala) { #hello-world-actor }

Java
:  @@snip [IntroSpec.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/fromclassic/TypedSample.java) { #hello-world-actor }

Why is it called `Behavior` and not `Actor`?

In Typed, the `Behavior` defines how to handle incoming messages. After processing a message, a different
`Behavior` may be returned for processing the next message. This means that an actor is started with an
initial `Behavior` and may change `Behavior` over its lifecycle. This is described more in the section
about @ref:[become](#become).

Note that the `Behavior` has a type parameter describing the type of messages that it can handle. This information
is not defined explicitly for a classic actor.

Links to reference documentation:

* @ref:[Classic](../actors.md#defining-an-actor-class)
* @ref:[Typed](actors.md#first-example)

## actorOf and Props

A classic actor is started with the `actorOf` method of the `ActorContext` or `ActorSystem`.

Corresponding method in Typed is called `spawn` in the @scala[`akka.actor.typed.scaladsl.ActorContext`]@java[`akka.actor.typed.javadsl.ActorContext`].

There is no `spawn` method in the @scala[`akka.actor.typed.scaladsl.ActorSystem`]@java[`akka.actor.typed.javadsl.ActorSystem`]
for creating top level actors. Instead, there is a single top level actor defined by a user guardian `Behavior` that is given
when starting the `ActorSystem`. Other actors are started as children of that user guardian actor or
children of other actors in the actor hierarchy. This is explained more in @ref:[ActorSystem](#actorsystem).

Note that when mixing classic and typed and have a classic system, spawning top level actors from the side is possible, see
@ref:[Coexistence](coexisting.md#top-level-typed-actor-classic-system).

The `actorOf` method takes an `akka.actor.Props` parameter, which is like a factory for creating the actor instance, and it's
also used when creating a new instance when the actor is restarted. The `Props` may also define additional
properties such as which dispatcher to use for the actor.

In typed, the `spawn` method creates an actor directly from a given `Behavior` without using a `Props` factory.
It does however accept an optional `akka.actor.typed.Props` for specifying Actor metadata.
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
when creating an `ActorSystem` in Typed you give it a `Behavior` that will be used as the top level actor, also known 
as the user guardian.

Additional actors for an application are created from the user guardian alongside performing the initialization
of Akka components such as Cluster Sharding. In contrast, in a classic `ActorSystem`, such initialization is
typically performed from the "outside".

The `actorOf` method of the classic `ActorSystem` is typically used to create a few (or many) top level actors. The
`ActorSystem` in Typed doesn't have that capability. Instead, such actors are started as children of
the user guardian actor or children of other actors in the actor hierarchy. The rationale for this is partly about consistency. 
In a typed system you can’t create children to an arbitrary actor from anywhere in your app without messaging it, 
so this will also hold true for the user guardian actor. That noted, in cases where you do need to spawn outside of this guardian 
then you can use the @ref:[`SpawnProtocol`](./actor-lifecycle.md#spawnprotocol) to spawn as needed.

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

## Supervision

An important difference between classic and typed is that in typed, actors are stopped by default if
an exception is thrown and no supervision strategy is defined. In contrast,
in classic, by default, actors are restarted.

In classic actors the supervision strategy for child actors are defined by overriding the `supervisorStrategy`
method in the parent actor.

In Typed the supervisor strategy is defined by wrapping the `Behavior` of the child actor with `Behaviors.supervise`.

The classic `BackoffSupervisor` is supported by `SupervisorStrategy.restartWithBackoff` as an ordinary
`SupervisorStrategy` in Typed.

`SupervisorStrategy.Escalate` isn't supported in Typed, but similar can be achieved as described in
@ref:[Bubble failures up through the hierarchy](fault-tolerance.md#bubble-failures-up-through-the-hierarchy).

Links to reference documentation:

* @ref:[Classic](../fault-tolerance.md)
* @ref:[Typed](fault-tolerance.md)

## Lifecycle hooks

Classic actors have methods `preStart`, `preRestart`, `postRestart` and `postStop` that can be overridden
to act on changes to the actor's lifecycle.

This is supported with corresponding `PreRestart` and `PostStop` signal messages in Typed. There are no
`PreStart` and `PostRestart` signals because such action can be done from `Behaviors.setup` or the
constructor of the `AbstractBehavior` class.

Note that in classic, the `postStop` lifecycle hook is also called when the
actor is restarted. That is not the case in Typed, only the
`PreRestart` signal is emitted. If you need to do resource cleanup on both restart and stop, you have to do
that for both `PreRestart` and `PostStop`.

Links to reference documentation:

* @ref:[Classic](../actors.md#start-hook)
* @ref:[Typed](fault-tolerance.md#the-prerestart-signal)

## watch

`watch` and the `Terminated` message are pretty much the same, with some additional capabilities in Typed.

`Terminated` is a signal in Typed since it is a different type than the declared message type of the `Behavior`.

The `watchWith` method of the `ActorContext` in Typed can be used to send a message instead of the `Terminated` signal.

When watching child actors it's possible to see if the child terminated voluntarily or due to a failure via the
`ChildFailed` signal, which is a subclass of `Terminated`.

Links to reference documentation:

* @ref:[Classic](../actors.md#deathwatch)
* @ref:[Typed](actor-lifecycle.md#watching-actors)

## Stopping

Classic actors can be stopped with the `stop` method of `ActorContext` or `ActorSystem`. In Typed an actor stops 
itself by returning `Behaviors.stopped`. There is also a `stop` method in the `ActorContext` but it
can only be used for stopping direct child actors and not any arbitrary actor.

`PoisonPill` is not supported in Typed. Instead, if you need to request an actor to stop you should
define a message that the actor understands and let it return `Behaviors.stopped` when receiving that message.

Links to reference documentation:

* @ref:[Classic](../actors.md#stopping-actors)
* @ref:[Typed](actor-lifecycle.md#stopping-actors)

## ActorSelection

`ActorSelection` isn't supported in Typed. Instead the @ref:[Receptionist](actor-discovery.md) is supposed to
be used for finding actors by a registered key.

`ActorSelection` can be used for sending messages to a path without having an `ActorRef` of the destination.
Note that a @ref:[Group Router](routers.md#group-router) can be used for that.

Links to reference documentation:

* @ref:[Classic](../actors.md#actorselection)
* @ref:[Typed](actor-discovery.md)

## ask

The classic `ask` pattern returns a @scala[`Future`]@java[`CompletionStage`] for the response.

Corresponding `ask` exists in Typed and is good when the requester itself isn't an actor.
It is located in @scala[`akka.actor.typed.scaladsl.AskPattern`]@java[`akka.actor.typed.javadsl.AskPattern`].

When the requester is an actor it is better to use the `ask` method of the `ActorContext` in Typed.
It has the advantage of not having to mix @scala[`Future`]@java[`CompletionStage`] callbacks that are
running on different threads with actor code.

Links to reference documentation:

* @ref:[Classic](../actors.md#actors-ask)
* @ref:[Typed](interaction-patterns.md#request-response-with-ask-between-two-actors)

## pipeTo

`pipeTo` is typically used together with `ask` in an actor. The `ask` method of the `ActorContext` in Typed
removes the need for `pipeTo`. However, for interactions with other APIs that return
@scala[`Future`]@java[`CompletionStage`] it is still useful to send the result as a message to the actor.
For this purpose there is a `pipeToSelf` method in the `ActorContext` in Typed.

## ActorContext

The `ActorContext` is always 1:1 with instance of an actor, when an actor has been started it exists even if you do not 
access it in any behavior that the actor has during its lifetime. When the actor is stopped and is garbage collected, 
so is the actor context. Multiple nested setup blocks will just give access to the same actor context and 
is not a problem.

You can think of the `ActorContext` as parallel to what `ActorRef` is for the actor from the “outside”, but from 
the “inside”. It gives access to operations that are associated with the actor instance, for example spawning
children, emitting log entries etc. that should only ever be used by the current behavior of the actor.

The definition of an actor is a computational entity that in response to a message can:

* send messages to other actors
* create new actors
* change its state
* designate the behavior to be used for the next message it receives

In Akka this boils down to a running actor having a current behavior to use when receiving the next message, 
access to a way to spawn children and optional state.

In the classic API this is directly and always modelled as a class @scala[`Actor`]@java[`AbstractActor`]. 
While this at first glance seems simple the running actor is in fact more of a pair of the actor class instance
and the actor context (which for example contains the current behavior `Receive` and `self`).

In the new APIs this can be modelled both in the same way using a class based `AbstractBehavior` which the actor keeps
for its entire life with state modelled as mutable fields, but also with a more FP:ish-style where the behavior and
state is separated and the actor often returns a new behavior and state pair in response to a message. The running
actor is in this case also essentially a pair of the actor context and the current behavior.

If you look at the current implementation of the new APIs you can see that it is in fact built on top of the classic
APIs, spawning a typed actor always spawns a classic actor under the hood.

## ActorContext.children

The `ActorContext` has methods @scala[`children` and `child`]@java[`getChildren` and `getChild`]
to retrieve the `ActorRef` of started child actors in both Typed and Classic.

The type of the returned `ActorRef` is unknown, since different types can be used for different
children. Therefore, this is not a useful way to lookup children when the purpose is to send
messages to them.

Instead of finding children via the `ActorContext`, it is recommended to use an application specific
collection for bookkeeping of children, such as a @scala[`Map[String, ActorRef[Child.Command]]`]
@java[`Map<String, ActorRef<Child.Command>>`]. It can look like this:

Scala
:  @@snip [IntroSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/fromclassic/TypedSample.scala) { #children }

Java
:  @@snip [IntroSpec.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/fromclassic/TypedSample.java) { #children }

Remember to remove entries from the `Map` when the children are terminated. For that purpose it's
convenient to use `watchWith`, as illustrated in the example above, because then you can include the
key to the `Map` in the termination message. In that way the name of the actor doesn't have to be
the same as identifier used for bookkeeping.

Retrieving the children from the `ActorContext` can still be useful for some use cases, such as:

* see if a child name is in use
* stopping children
* the type of the child is well known and `unsafeUpcast` of the `ActorRef` is considered "safe enough"

## Remote deployment

Starting an actor on a remote node—so called remote deployment—isn't supported in Typed.

This feature would be discouraged because it often results in tight coupling between nodes and undesirable
failure handling. For example if the node of the parent actor crashes, all remote deployed child actors are
brought down with it. Sometimes that can be desired but many times it is used without realizing. This can be
achieved by other means, such as using `watch`.

## Routers

Routers are provided in Typed, but in a much simplified form compared to the classic routers.

Destinations of group routers are registered in the `Receptionist`, which makes them Cluster aware and also
more dynamic than classic group routers.

Pool routers are only for local actor destinations in Typed, since @ref:[remote deployment isn't supported](#remote-deployment).

Links to reference documentation:

* @ref:[Classic](../routing.md)
* @ref:[Typed](routers.md)

## FSM

With classic actors there is explicit support for building Finite State Machines. No support is needed in
Akka Typed as it is straightforward to represent FSMs with behaviors.

Links to reference documentation:

* @ref:[Classic](../fsm.md)
* @ref:[Typed](fsm.md)

## Timers

In classic actors you @scala[mixin `with Timers`]@java[`extend AbstractActorWithTimers`] to gain access to
delayed and periodic scheduling of messages. In Typed you have access to similar capabilities via `Behaviors.withTimers`.

Links to reference documentation:

* @ref:[Classic](../actors.md#actors-timers)
* @ref:[Typed](interaction-patterns.md#scheduling-messages-to-self)

## Stash

In classic actors you @scala[mixin `with Stash`]@java[`extend AbstractActorWithStash`] to gain access to
stashing of messages. In Typed you have access to similar capabilities via `Behaviors.withStash`.

Links to reference documentation:

* @ref:[Classic](../actors.md#stash)
* @ref:[Typed](stash.md)

## PersistentActor

The correspondence of the classic `PersistentActor` is @scala[`akka.persistence.typed.scaladsl.EventSourcedBehavior`]@java[`akka.persistence.typed.javadsl.EventSourcedBehavior`].

The Typed API is much more guided to facilitate Event Sourcing best practices. It also has tighter integration with
Cluster Sharding.

Links to reference documentation:

* @ref:[Classic](../persistence.md)
* @ref:[Typed](persistence.md)

## Asynchronous Testing

The Test Kits for asynchronous testing are rather similar.

Links to reference documentation:

* @ref:[Classic](../testing.md#async-integration-testing)
* @ref:[Typed](testing-async.md#asynchronous-testing)

## Synchronous Testing

Classic and typed have different Test Kits for synchronous testing.

Behaviors in Typed can be tested in isolation without having to be packaged into an actor.
As a consequence, tests can run fully synchronously without having to worry about timeouts and spurious failures.

The `BehaviorTestKit` provides a nice way of unit testing a `Behavior` in a deterministic way, but it has
some limitations to be aware of. Similar limitations exists for synchronous testing of classic actors.

Links to reference documentation:

* @ref:[Classic](../testing.md#sync-testing)
* @ref:[Typed](testing-sync.md#synchronous-behavior-testing)
