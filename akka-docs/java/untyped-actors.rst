
.. _untyped-actors-java:

################
 Actors (Java)
################


.. sidebar:: Contents

   .. contents:: :local:


The `Actor Model`_ provides a higher level of abstraction for writing concurrent
and distributed systems. It alleviates the developer from having to deal with
explicit locking and thread management, making it easier to write correct
concurrent and parallel systems. Actors were defined in the 1973 paper by Carl
Hewitt but have been popularized by the Erlang language, and used for example at
Ericsson with great success to build highly concurrent and reliable telecom
systems.

The API of Akka’s Actors is similar to Scala Actors which has borrowed some of
its syntax from Erlang.

.. _Actor Model: http://en.wikipedia.org/wiki/Actor_model


Creating Actors
===============


Defining an Actor class
-----------------------

Actor in Java are implemented by extending the ``UntypedActor`` class and implementing the
:meth:`onReceive` method. This method takes the message as a parameter.

Here is an example:

.. includecode:: code/akka/docs/actor/MyUntypedActor.java#my-untyped-actor

Creating Actors with default constructor
----------------------------------------

.. includecode:: code/akka/docs/actor/UntypedActorTestBase.java
   :include: imports,system-actorOf

The call to :meth:`actorOf` returns an instance of ``ActorRef``. This is a handle to
the ``UntypedActor`` instance which you can use to interact with the ``UntypedActor``. The
``ActorRef`` is immutable and has a one to one relationship with the Actor it
represents. The ``ActorRef`` is also serializable and network-aware. This means
that you can serialize it, send it over the wire and use it on a remote host and
it will still be representing the same Actor on the original node, across the
network.

In the above example the actor was created from the system. It is also possible
to create actors from other actors with the actor ``context``. The difference is
how the supervisor hierarchy is arranged. When using the context the current actor
will be supervisor of the created child actor. When using the system it will be
a top level actor, that is supervised by the system (internal guardian actor).

.. includecode:: code/akka/docs/actor/FirstUntypedActor.java#context-actorOf

Actors are automatically started asynchronously when created.
When you create the ``UntypedActor`` then it will automatically call the ``preStart``
callback method on the ``UntypedActor`` class. This is an excellent place to
add initialization code for the actor.

.. code-block:: java

  @Override
  public void preStart() {
    ... // initialization code
  }

Creating Actors with non-default constructor
--------------------------------------------

If your UntypedActor has a constructor that takes parameters then you can't create it using 'actorOf(clazz)'.
Instead you can use a variant of ``actorOf`` that takes an instance of an 'UntypedActorFactory'
in which you can create the Actor in any way you like. If you use this method then you to make sure that
no one can get a reference to the actor instance. If they can get a reference it then they can
touch state directly in bypass the whole actor dispatching mechanism and create race conditions
which can lead to corrupt data.

Here is an example:

.. includecode:: code/akka/docs/actor/UntypedActorTestBase.java#creating-constructor

This way of creating the Actor is also great for integrating with Dependency Injection (DI) frameworks like Guice or Spring.

Creating Actors with Props
--------------------------

``Props`` is a configuration object to specify additional things for the actor to
be created, such as the ``MessageDispatcher``.

.. includecode:: code/akka/docs/actor/UntypedActorTestBase.java#creating-props


UntypedActor API
================

The :class:`UntypedActor` class defines only one abstract method, the above mentioned
:meth:`onReceive(Object message)`, which implements the behavior of the actor.

In addition, it offers:

* :obj:`getSelf()` reference to the :class:`ActorRef` of the actor
* :obj:`getSender()` reference sender Actor of the last received message, typically used as described in :ref:`UntypedActor.Reply`
* :obj:`getContext()` exposes contextual information for the actor and the current message, such as:

  * factory methods to create child actors (:meth:`actorOf`)
  * system that the actor belongs to
  * parent supervisor
  * supervised children
  * hotswap behavior stack as described in :ref:`UntypedActor.HotSwap`

The remaining visible methods are user-overridable life-cycle hooks which are
described in the following:

.. includecode:: code/akka/docs/actor/UntypedActorTestBase.java#lifecycle-callbacks

The implementations shown above are the defaults provided by the :class:`UntypedActor`
class.


Start Hook
----------

Right after starting the actor, its :meth:`preStart` method is invoked.

::

  @Override
  public void preStart() {
    // registering with other actors
    someService.tell(Register(getSelf());
  }


Restart Hooks
-------------

All actors are supervised, i.e. linked to another actor with a fault
handling strategy. Actors will be restarted in case an exception is thrown while
processing a message. This restart involves the hooks mentioned above:

1. The old actor is informed by calling :meth:`preRestart` with the exception
   which caused the restart and the message which triggered that exception; the
   latter may be ``None`` if the restart was not caused by processing a
   message, e.g. when a supervisor does not trap the exception and is restarted
   in turn by its supervisor. This method is the best place for cleaning up,
   preparing hand-over to the fresh actor instance, etc.
   By default it stops all children and calls :meth:`postStop`.
2. The initial factory from the ``actorOf`` call is used
   to produce the fresh instance.
3. The new actor’s :meth:`postRestart` method is invoked with the exception
   which caused the restart. By default the :meth:`preStart`
   is called, just as in the normal start-up case.


An actor restart replaces only the actual actor object; the contents of the
mailbox and the hotswap stack are unaffected by the restart, so processing of
messages will resume after the :meth:`postRestart` hook returns. The message
that triggered the exception will not be received again. Any message
sent to an actor while it is being restarted will be queued to its mailbox as
usual.

Stop Hook
---------

After stopping an actor, its :meth:`postStop` hook is called, which may be used
e.g. for deregistering this actor from other services. This hook is guaranteed
to run after message queuing has been disabled for this actor, i.e. messages
sent to a stopped actor will be redirected to the :obj:`deadLetters` of the
:obj:`ActorSystem`.


Identifying Actors
==================

FIXME Actor Path documentation


Messages and immutability
=========================

**IMPORTANT**: Messages can be any kind of object but have to be
immutable. Akka can’t enforce immutability (yet) so this has to be by
convention.

Here is an example of an immutable message:

.. includecode:: code/akka/docs/actor/ImmutableMessage.java#immutable-message


Send messages
=============

Messages are sent to an Actor through one of the following methods.

* ``tell`` means “fire-and-forget”, e.g. send a message asynchronously and return
  immediately.
* ``ask`` sends a message asynchronously and returns a :class:`Future`
  representing a possible reply.

Message ordering is guaranteed on a per-sender basis.

In all these methods you have the option of passing along your own ``ActorRef``.
Make it a practice of doing so because it will allow the receiver actors to be able to respond
to your message, since the sender reference is sent along with the message.

Tell: Fire-forget
-----------------

This is the preferred way of sending messages. No blocking waiting for a
message. This gives the best concurrency and scalability characteristics.

.. code-block:: java

  actor.tell("Hello");

Or with the sender reference passed along with the message and available to the receiving Actor
in its ``getSender: ActorRef`` member field. The target actor can use this
to reply to the original sender, by using ``getSender().tell(replyMsg)``.

.. code-block:: java

  actor.tell("Hello", getSelf());

If invoked without the sender parameter the sender will be
:obj:`deadLetters` actor reference in the target actor.

Ask: Send-And-Receive-Future
----------------------------

Using ``ask`` will send a message to the receiving Actor asynchronously and
will immediately return a :class:`Future`:

.. code-block:: java

  long timeoutMillis = 1000;
  Future future = actorRef.ask("Hello", timeoutMillis);

The receiving actor should reply to this message, which will complete the
future with the reply message as value; ``getSender.tell(result)``.

To complete the future with an exception you need send a Failure message to the sender.
This is not done automatically when an actor throws an exception while processing a
message.

.. includecode:: code/akka/docs/actor/UntypedActorTestBase.java#reply-exception

If the actor does not complete the future, it will expire after the timeout period,
specified as parameter to the ``ask`` method.

See :ref:`futures-java` for more information on how to await or query a
future.

The ``onComplete``, ``onResult``, or ``onTimeout`` methods of the ``Future`` can be
used to register a callback to get a notification when the Future completes.
Gives you a way to avoid blocking.

.. warning::

  When using future callbacks, inside actors you need to carefully avoid closing over
  the containing actor’s reference, i.e. do not call methods or access mutable state
  on the enclosing actor from within the callback. This would break the actor
  encapsulation and may introduce synchronization bugs and race conditions because
  the callback will be scheduled concurrently to the enclosing actor. Unfortunately
  there is not yet a way to detect these illegal accesses at compile time. See also:
  :ref:`jmm-shared-state`

The future returned from the ``ask`` method can conveniently be passed around or
chained with further processing steps, but sometimes you just need the value,
even if that entails waiting for it (but keep in mind that waiting inside an
actor is prone to dead-locks, e.g. if obtaining the result depends on
processing another message on this actor).

.. includecode:: code/akka/docs/actor/UntypedActorTestBase.java
   :include: import-future,using-ask

Forward message
---------------

You can forward a message from one actor to another. This means that the
original sender address/reference is maintained even though the message is going
through a 'mediator'. This can be useful when writing actors that work as
routers, load-balancers, replicators etc.
You need to pass along your context variable as well.

.. code-block:: java

  myActor.forward(message, getContext());

Receive messages
================

When an actor receives a message it is passed into the ``onReceive`` method, this is
an abstract method on the ``UntypedActor`` base class that needs to be defined.

Here is an example:

.. includecode:: code/akka/docs/actor/MyUntypedActor.java#my-untyped-actor

An alternative to using if-instanceof checks is to use `Apache Commons MethodUtils
<http://commons.apache.org/beanutils/api/org/apache/commons/beanutils/MethodUtils.html#invokeMethod(java.lang.Object,%20java.lang.String,%20java.lang.Object)>`_
to invoke a named method whose parameter type matches the message type.

.. _UntypedActor.Reply:

Reply to messages
=================

If you want to have a handle for replying to a message, you can use
``getSender()``, which gives you an ActorRef. You can reply by sending to
that ActorRef with ``getSender().tell(replyMsg)``. You can also store the ActorRef
for replying later, or passing on to other actors. If there is no sender (a
message was sent without an actor or future context) then the sender
defaults to a 'dead-letter' actor ref.

.. code-block:: java

  public void onReceive(Object request) {
    String result = process(request);
    getSender().tell(result);       // will have dead-letter actor as default
  }

Initial receive timeout
=======================

A timeout mechanism can be used to receive a message when no initial message is
received within a certain time. To receive this timeout you have to set the
``receiveTimeout`` property and declare handing for the ReceiveTimeout
message.

.. includecode:: code/akka/docs/actor/MyReceivedTimeoutUntypedActor.java#receive-timeout

Stopping actors
===============

Actors are stopped by invoking the :meth:`stop` method of a ``ActorRefFactory``,
i.e. ``ActorContext`` or ``ActorSystem``. Typically the context is used for stopping
child actors and the system for stopping top level actors. When using the context
to stop an actor the actual termination of the actor is performed asynchronously,
i.e. :meth:`stop` may return before the actor is stopped. When using the system to
stop an actor the :meth:`stop` method will block until the actor is stopped, or
timeout occurs (``akka.actor.creation-timeout`` :ref:`configuration` property).

Processing of the current message, if any, will continue before the actor is stopped,
but additional messages in the mailbox will not be processed. By default these
messages are sent to the :obj:`deadLetters` of the :obj:`ActorSystem`, but that
depends on the mailbox implementation.

When stop is called then a call to the ``def postStop`` callback method will
take place. The ``Actor`` can use this callback to implement shutdown behavior.

.. code-block:: java

  public void postStop() {
    ... // clean up resources
  }


All Actors are stopped when the ``ActorSystem`` is stopped.
Supervised actors are stopped when the supervisor is stopped, i.e. children are stopped
when parent is stopped.


PoisonPill
----------

You can also send an actor the ``akka.actor.PoisonPill`` message, which will
stop the actor when the message is processed. ``PoisonPill`` is enqueued as
ordinary messages and will be handled after messages that were already queued
in the mailbox.

If the ``PoisonPill`` was sent with ``ask``, the ``Future`` will be completed with an
``akka.actor.ActorKilledException("PoisonPill")``.

Use it like this:

.. includecode:: code/akka/docs/actor/UntypedActorTestBase.java
   :include: import-actors,poison-pill

.. _UntypedActor.HotSwap:

HotSwap
=======

Upgrade
-------

Akka supports hotswapping the Actor’s message loop (e.g. its implementation) at
runtime. Use the ``getContext().become`` method from within the Actor.
The hotswapped code is kept in a Stack which can be pushed and popped.

.. warning::

  Please note that the actor will revert to its original behavior when restarted by its Supervisor.

To hotswap the Actor using ``getContext().become``:

.. includecode:: code/akka/docs/actor/UntypedActorTestBase.java
   :include: import-procedure,hot-swap-actor

The ``become`` method is useful for many different things, such as to implement
a Finite State Machine (FSM).

Here is another little cute example of ``become`` and ``unbecome`` in action:

.. includecode:: code/akka/docs/actor/UntypedActorSwapper.java#swapper

Downgrade
---------

Since the hotswapped code is pushed to a Stack you can downgrade the code as
well. Use the ``getContext().unbecome`` method from within the Actor.

.. code-block:: java

  public void onReceive(Object message) {
    if (message.equals("revert")) getContext().unbecome();
  }

Killing an Actor
================

You can kill an actor by sending a ``Kill`` message. This will restart the actor
through regular supervisor semantics.

Use it like this:

.. includecode:: code/akka/docs/actor/UntypedActorTestBase.java
   :include: import-actors,kill

Actors and exceptions
=====================

It can happen that while a message is being processed by an actor, that some
kind of exception is thrown, e.g. a database exception.

What happens to the Message
---------------------------

If an exception is thrown while a message is being processed (so taken of his
mailbox and handed over the the receive), then this message will be lost. It is
important to understand that it is not put back on the mailbox. So if you want
to retry processing of a message, you need to deal with it yourself by catching
the exception and retry your flow. Make sure that you put a bound on the number
of retries since you don't want a system to livelock (so consuming a lot of cpu
cycles without making progress).

What happens to the mailbox
---------------------------

If an exception is thrown while a message is being processed, nothing happens to
the mailbox. If the actor is restarted, the same mailbox will be there. So all
messages on that mailbox, will be there as well.

What happens to the actor
-------------------------

If an exception is thrown, the actor instance is discarded and a new instance is
created. This new instance will now be used in the actor references to this actor
(so this is done invisible to the developer). Note that this means that current
state of the failing actor instance is lost if you don't store and restore it in
``preRestart`` and ``postRestart`` callbacks.

