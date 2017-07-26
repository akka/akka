# Typed Actors

@@@ note

This module will be deprecated as it will be superseded by the @ref:[Akka Typed](typed.md)
project which is currently being developed in open preview mode.

@@@

Akka Typed Actors is an implementation of the [Active Objects](http://en.wikipedia.org/wiki/Active_object) pattern.
Essentially turning method invocations into asynchronous dispatch instead of synchronous that has been the default way since Smalltalk came out.

Typed Actors consist of 2 "parts", a public interface and an implementation, and if you've done any work in "enterprise" Java, this will be very familiar to you. As with normal Actors you have an external API (the public interface instance) that will delegate method calls asynchronously to
a private instance of the implementation.

The advantage of Typed Actors vs. Actors is that with TypedActors you have a
static contract, and don't need to define your own messages, the downside is
that it places some limitations on what you can do and what you can't, i.e. you
cannot use `become`/`unbecome`.

Typed Actors are implemented using [JDK Proxies](http://docs.oracle.com/javase/6/docs/api/java/lang/reflect/Proxy.html) which provide a pretty easy-worked API to intercept method calls.

@@@ note

Just as with regular Akka Actors, Typed Actors process one call at a time.

@@@

## When to use Typed Actors

Typed actors are nice for bridging between actor systems (the “inside”) and
non-actor code (the “outside”), because they allow you to write normal
OO-looking code on the outside. Think of them like doors: their practicality
lies in interfacing between private sphere and the public, but you don’t want
that many doors inside your house, do you? For a longer discussion see [this
blog post](http://letitcrash.com/post/19074284309/when-to-use-typedactors).

A bit more background: TypedActors can easily be abused as RPC, and that
is an abstraction which is [well-known](http://doc.akka.io/docs/misc/smli_tr-94-29.pdf)
to be leaky. Hence TypedActors are not what we think of first when we talk
about making highly scalable concurrent software easier to write correctly.
They have their niche, use them sparingly.

## The tools of the trade

Before we create our first Typed Actor we should first go through the tools that we have at our disposal,
it's located in `akka.actor.TypedActor`.

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-extension-tools }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-extension-tools }

@@@ warning

Same as not exposing `this` of an Akka Actor, it's important not to expose `this` of a Typed Actor,
instead you should pass the external proxy reference, which is obtained from within your Typed Actor as
@scala[`TypedActor.self`]@java[`TypedActor.self()`], this is your external identity, as the `ActorRef` is the external identity of
an Akka Actor.

@@@

## Creating Typed Actors

To create a Typed Actor you need to have one or more interfaces, and one implementation.

The following imports are assumed:

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #imports }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #imports }

Our example interface:

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-iface }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-iface }

Our example implementation of that interface:

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-impl }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-impl }

The most trivial way of creating a Typed Actor instance
of our `Squarer`:

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-create1 }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-create1 }

First type is the type of the proxy, the second type is the type of the implementation.
If you need to call a specific constructor you do it like this:

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-create2 }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-create2 }

Since you supply a `Props`, you can specify which dispatcher to use, what the default timeout should be used and more.
Now, our `Squarer` doesn't have any methods, so we'd better add those.

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-iface }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-iface }

Alright, now we've got some methods we can call, but we need to implement those in `SquarerImpl`.

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-impl }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-impl }

Excellent, now we have an interface and an implementation of that interface,
and we know how to create a Typed Actor from that, so let's look at calling these methods.

## Method dispatch semantics

Methods returning:

@@@ div {.group-scala}

 * `Unit` will be dispatched with `fire-and-forget` semantics, exactly like `ActorRef.tell`
 * `scala.concurrent.Future[_]` will use `send-request-reply` semantics, exactly like `ActorRef.ask`
 * `scala.Option[_]` will use `send-request-reply` semantics, but *will* block to wait for an answer,
and return `scala.None` if no answer was produced within the timeout, or `scala.Some[_]` containing the result otherwise.
Any exception that was thrown during this call will be rethrown.
 * Any other type of value will use `send-request-reply` semantics, but *will* block to wait for an answer,
throwing `java.util.concurrent.TimeoutException` if there was a timeout or rethrow any exception that was thrown during this call.

@@@

@@@ div {.group-java}

 * `void` will be dispatched with `fire-and-forget` semantics, exactly like `ActorRef.tell`
 * `scala.concurrent.Future<?>` will use `send-request-reply` semantics, exactly like `ActorRef.ask`
 * `akka.japi.Option<?>` will use `send-request-reply` semantics, but *will* block to wait for an answer,
and return `akka.japi.Option.None` if no answer was produced within the timeout, or `akka.japi.Option.Some<?>` containing the result otherwise.
Any exception that was thrown during this call will be rethrown.
 * Any other type of value will use `send-request-reply` semantics, but *will* block to wait for an answer,
throwing `java.util.concurrent.TimeoutException` if there was a timeout or rethrow any exception that was thrown during this call.
Note that due to the Java exception and reflection mechanisms, such a `TimeoutException` will be wrapped in a `java.lang.reflect.UndeclaredThrowableException`
unless the interface method explicitly declares the `TimeoutException` as a thrown checked exception.

@@@


## Messages and immutability

While Akka cannot enforce that the parameters to the methods of your Typed Actors are immutable,
we *strongly* recommend that parameters passed are immutable.

### One-way message send

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-call-oneway }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-call-oneway }

As simple as that! The method will be executed on another thread; asynchronously.

### Request-reply message send

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-call-option }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-call-option }

This will block for as long as the timeout that was set in the `Props` of the Typed Actor,
if needed. It will return `None` if a timeout occurs.

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-call-strict }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-call-strict }

This will block for as long as the timeout that was set in the `Props` of the Typed Actor,
if needed. It will throw a `java.util.concurrent.TimeoutException` if a timeout occurs.

@@@ div {.group-java}

Note that here, such a `TimeoutException` will be wrapped in a
`java.lang.reflect.UndeclaredThrowableException` by the Java reflection mechanism,
because the interface method does not explicitly declare the `TimeoutException` as a thrown checked exception.
To get the `TimeoutException` directly, declare `throws java.util.concurrent.TimeoutException` at the
interface method.

@@@

### Request-reply-with-future message send

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-call-future }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-call-future }

This call is asynchronous, and the Future returned can be used for asynchronous composition.

## Stopping Typed Actors

Since Akka's Typed Actors are backed by Akka Actors they must be stopped when they aren't needed anymore.

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-stop }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-stop }

This asynchronously stops the Typed Actor associated with the specified proxy ASAP.

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-poisonpill }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-poisonpill }

This asynchronously stops the Typed Actor associated with the specified proxy
after it's done with all calls that were made prior to this call.

## Typed Actor Hierarchies

Since you can obtain a contextual Typed Actor Extension by passing in an `ActorContext`
you can create child Typed Actors by invoking `typedActorOf(..)` on that.

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-hierarchy }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-hierarchy }

You can also create a child Typed Actor in regular Akka Actors by giving the @scala[`ActorContext`]@java[`AbstractActor.ActorContext`]
as an input parameter to `TypedActor.get(…)`.

## Supervisor Strategy

By having your Typed Actor implementation class implement `TypedActor.Supervisor`
you can define the strategy to use for supervising child actors, as described in
@ref:[supervision](general/supervision.md) and @ref:[Fault Tolerance](fault-tolerance.md).

## Lifecycle callbacks

By having your Typed Actor implementation class implement any and all of the following:

 * `TypedActor.PreStart`
 * `TypedActor.PostStop`
 * `TypedActor.PreRestart`
 * `TypedActor.PostRestart`

You can hook into the lifecycle of your Typed Actor.

## Receive arbitrary messages

If your implementation class of your TypedActor extends `akka.actor.TypedActor.Receiver`,
all messages that are not `MethodCall` instances will be passed into the `onReceive`-method.

This allows you to react to DeathWatch `Terminated`-messages and other types of messages,
e.g. when interfacing with untyped actors.

## Proxying

You can use the `typedActorOf` that takes a TypedProps and an ActorRef to proxy the given ActorRef as a TypedActor.
This is usable if you want to communicate remotely with TypedActors on other machines, just pass the `ActorRef` to `typedActorOf`.

@@@ note

The ActorRef needs to accept `MethodCall` messages.

@@@

## Lookup & Remoting

Since `TypedActors` are backed by `Akka Actors`, you can use `typedActorOf` to proxy `ActorRefs` potentially residing on remote nodes.

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-remote }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-actor-remote }

@@@ div {.group-scala}

## Supercharging

Here's an example on how you can use traits to mix in behavior in your Typed Actors.

@@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-supercharge }

@@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-actor-supercharge-usage }

@@@

## Typed Router pattern

Sometimes you want to spread messages between multiple actors. The easiest way to achieve this in Akka is to use a @ref:[Router](routing.md),
which can implement a specific routing logic, such as `smallest-mailbox` or `consistent-hashing` etc.

Routers are not provided directly for typed actors, but it is really easy to leverage an untyped router and use a typed proxy in front of it.
To showcase this let's create typed actors that assign themselves some random `id`, so we know that in fact, the router has sent the message to different actors:

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-router-types }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-router-types }

In order to round robin among a few instances of such actors, you can simply create a plain untyped router,
and then facade it with a `TypedActor` like shown in the example below. This works because typed actors of course
communicate using the same mechanisms as normal actors, and methods calls on them get transformed into message sends of `MethodCall` messages.

Scala
: @@snip [TypedActorDocSpec.scala]($code$/scala/docs/actor/TypedActorDocSpec.scala) { #typed-router }

Java
: @@snip [TypedActorDocTest.java]($code$/java/jdocs/actor/TypedActorDocTest.java) { #typed-router }
