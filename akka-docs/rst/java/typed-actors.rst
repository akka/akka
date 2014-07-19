Typed Actors
===================

Akka Typed Actors is an implementation of the `Active Objects <http://en.wikipedia.org/wiki/Active_object>`_ pattern.
Essentially turning method invocations into asynchronous dispatch instead of synchronous that has been the default way since Smalltalk came out.

Typed Actors consist of 2 "parts", a public interface and an implementation, and if you've done any work in "enterprise" Java, this will be very familiar to you. As with normal Actors you have an external API (the public interface instance) that will delegate methodcalls asynchronously to
a private instance of the implementation.

The advantage of Typed Actors vs. Actors is that with TypedActors you have a static contract, and don't need to define your own messages, the downside is that it places some limitations on what you can do and what you can't, i.e. you can't use become/unbecome.

Typed Actors are implemented using `JDK Proxies <http://docs.oracle.com/javase/6/docs/api/java/lang/reflect/Proxy.html>`_ which provide a pretty easy-worked API to intercept method calls.

.. note::

    Just as with regular Akka Untyped Actors, Typed Actors process one call at a time.

When to use Typed Actors
------------------------

Typed actors are nice for bridging between actor systems (the “inside”) and
non-actor code (the “outside”), because they allow you to write normal
OO-looking code on the outside. Think of them like doors: their practicality
lies in interfacing between private sphere and the public, but you don’t want
that many doors inside your house, do you? For a longer discussion see `this
blog post <http://letitcrash.com/post/19074284309/when-to-use-typedactors>`_.

A bit more background: TypedActors can very easily be abused as RPC, and that
is an abstraction which is `well-known
<http://doc.akka.io/docs/misc/smli_tr-94-29.pdf>`_
to be leaky. Hence TypedActors are not what we think of first when we talk
about making highly scalable concurrent software easier to write correctly.
They have their niche, use them sparingly.

The tools of the trade
----------------------

Before we create our first Typed Actor we should first go through the tools that we have at our disposal,
it's located in ``akka.actor.TypedActor``.

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-extension-tools

.. warning::

    Same as not exposing ``this`` of an Akka Actor, it's important not to expose ``this`` of a Typed Actor,
    instead you should pass the external proxy reference, which is obtained from within your Typed Actor as
    ``TypedActor.self()``, this is your external identity, as the ``ActorRef`` is the external identity of
    an Akka Actor.

Creating Typed Actors
---------------------

To create a Typed Actor you need to have one or more interfaces, and one implementation.

The following imports are assumed:

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: imports

Our example interface:

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-iface
   :exclude: typed-actor-iface-methods

Our example implementation of that interface:

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-impl
   :exclude: typed-actor-impl-methods

The most trivial way of creating a Typed Actor instance
of our ``Squarer``:

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-create1

First type is the type of the proxy, the second type is the type of the implementation.
If you need to call a specific constructor you do it like this:

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-create2

Since you supply a ``Props``, you can specify which dispatcher to use, what the default timeout should be used and more.
Now, our ``Squarer`` doesn't have any methods, so we'd better add those.

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-iface

Alright, now we've got some methods we can call, but we need to implement those in ``SquarerImpl``.

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-impl

Excellent, now we have an interface and an implementation of that interface,
and we know how to create a Typed Actor from that, so let's look at calling these methods.

Method dispatch semantics
-------------------------

Methods returning:

  * ``void`` will be dispatched with ``fire-and-forget`` semantics, exactly like ``ActorRef.tell``
  * ``scala.concurrent.Future<?>`` will use ``send-request-reply`` semantics, exactly like ``ActorRef.ask``
  * ``akka.japi.Option<?>`` will use ``send-request-reply`` semantics, but *will* block to wait for an answer,
    and return ``akka.japi.Option.None`` if no answer was produced within the timeout, or ``akka.japi.Option.Some<?>`` containing the result otherwise.
    Any exception that was thrown during this call will be rethrown.
  * Any other type of value will use ``send-request-reply`` semantics, but *will* block to wait for an answer,
    throwing ``java.util.concurrent.TimeoutException`` if there was a timeout or rethrow any exception that was thrown during this call.
    Note that due to the Java exception and reflection mechanisms, such a ``TimeoutException`` will be wrapped in a ``java.lang.reflect.UndeclaredThrowableException``
    unless the interface method explicitly declares the ``TimeoutException`` as a thrown checked exception.

Messages and immutability
-------------------------

While Akka cannot enforce that the parameters to the methods of your Typed Actors are immutable,
we *strongly* recommend that parameters passed are immutable.

One-way message send
^^^^^^^^^^^^^^^^^^^^

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-call-oneway

As simple as that! The method will be executed on another thread; asynchronously.

Request-reply message send
^^^^^^^^^^^^^^^^^^^^^^^^^^

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-call-option

This will block for as long as the timeout that was set in the ``Props`` of the Typed Actor,
if needed. It will return ``None`` if a timeout occurs.

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-call-strict

This will block for as long as the timeout that was set in the ``Props`` of the Typed Actor,
if needed. It will throw a ``java.util.concurrent.TimeoutException`` if a timeout occurs.
Note that here, such a ``TimeoutException`` will be wrapped in a
``java.lang.reflect.UndeclaredThrowableException`` by the Java reflection mechanism,
because the interface method does not explicitly declare the ``TimeoutException`` as a thrown checked exception.
To get the ``TimeoutException`` directly, declare ``throws java.util.concurrent.TimeoutException`` at the
interface method.

Request-reply-with-future message send
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-call-future

This call is asynchronous, and the Future returned can be used for asynchronous composition.

Stopping Typed Actors
---------------------

Since Akka's Typed Actors are backed by Akka Actors they must be stopped when they aren't needed anymore.

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-stop

This asynchronously stops the Typed Actor associated with the specified proxy ASAP.

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-poisonpill

This asynchronously stops the Typed Actor associated with the specified proxy
after it's done with all calls that were made prior to this call.

Typed Actor Hierarchies
-----------------------

Since you can obtain a contextual Typed Actor Extension by passing in an ``ActorContext``
you can create child Typed Actors by invoking ``typedActorOf(..)`` on that.

.. includecode:: code/docs/actor/TypedActorDocTest.java
   :include: typed-actor-hierarchy

You can also create a child Typed Actor in regular Akka Actors by giving the ``UntypedActorContext``
as an input parameter to TypedActor.get(…).

Supervisor Strategy
-------------------

By having your Typed Actor implementation class implement ``TypedActor.Supervisor``
you can define the strategy to use for supervising child actors, as described in
:ref:`supervision` and :ref:`fault-tolerance-java`.

Receive arbitrary messages
--------------------------

If your implementation class of your TypedActor extends ``akka.actor.TypedActor.Receiver``,
all messages that are not ``MethodCall``s will be passed into the ``onReceive``-method.

This allows you to react to DeathWatch ``Terminated``-messages and other types of messages,
e.g. when interfacing with untyped actors.

Lifecycle callbacks
-------------------

By having your Typed Actor implementation class implement any and all of the following:

    * ``TypedActor.PreStart``
    * ``TypedActor.PostStop``
    * ``TypedActor.PreRestart``
    * ``TypedActor.PostRestart``

You can hook into the lifecycle of your Typed Actor.

Proxying
--------

You can use the ``typedActorOf`` that takes a TypedProps and an ActorRef to proxy the given ActorRef as a TypedActor.
This is usable if you want to communicate remotely with TypedActors on other machines, just pass the ``ActorRef`` to ``typedActorOf``.

Lookup & Remoting
-----------------

Since ``TypedActors`` are backed by ``Akka Actors``, you can use ``typedActorOf`` to proxy ``ActorRefs`` potentially residing on remote nodes.

.. includecode:: code/docs/actor/TypedActorDocTest.java#typed-actor-remote

Typed Router pattern
--------------------

Sometimes you want to spread messages between multiple actors. The easiest way to achieve this in Akka is to use a :ref:`Router <routing-java>`,
which can implement a specific routing logic, such as ``smallest-mailbox`` or ``consistent-hashing`` etc.

Routers are not provided directly for typed actors, but it is really easy to leverage an untyped router and use a typed proxy in front of it.
To showcase this let's create typed actors that assign themselves some random ``id``, so we know that in fact, the router has sent the message to different actors:

.. includecode:: code/docs/actor/TypedActorDocTest.java#typed-router-types

In order to round robin among a few instances of such actors, you can simply create a plain untyped router,
and then facade it with a ``TypedActor`` like shown in the example below. This works because typed actors of course
communicate using the same mechanisms as normal actors, and methods calls on them get transformed into message sends of ``MethodCall`` messages.

.. includecode:: code/docs/actor/TypedActorDocTest.java#typed-router

