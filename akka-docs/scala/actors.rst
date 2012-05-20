
.. _actors-scala:

################
 Actors (Scala)
################


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

Since Akka enforces parental supervision every actor is supervised and
(potentially) the supervisor of its children; it is advisable that you
familiarize yourself with :ref:`actor-systems` and :ref:`supervision` and it
may also help to read :ref:`actorOf-vs-actorFor`.

Defining an Actor class
-----------------------

Actor classes are implemented by extending the Actor class and implementing the
:meth:`receive` method. The :meth:`receive` method should define a series of case
statements (which has the type ``PartialFunction[Any, Unit]``) that defines
which messages your Actor can handle, using standard Scala pattern matching,
along with the implementation of how the messages should be processed.

Here is an example:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala
   :include: imports1,my-actor

Please note that the Akka Actor ``receive`` message loop is exhaustive, which is
different compared to Erlang and Scala Actors. This means that you need to
provide a pattern match for all messages that it can accept and if you want to
be able to handle unknown messages then you need to have a default case as in
the example above. Otherwise an ``akka.actor.UnhandledMessage(message, sender, recipient)`` will be
published to the ``ActorSystem``'s ``EventStream``.

Creating Actors with default constructor
----------------------------------------

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala
   :include: imports2,system-actorOf

The call to :meth:`actorOf` returns an instance of ``ActorRef``. This is a handle to
the ``Actor`` instance which you can use to interact with the ``Actor``. The
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

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#context-actorOf

The name parameter is optional, but you should preferably name your actors, since
that is used in log messages and for identifying actors. The name must not be empty
or start with ``$``. If the given name is already in use by another child to the
same parent actor an `InvalidActorNameException` is thrown.

.. warning::

  Creating top-level actors with ``system.actorOf`` is a blocking operation,
  hence it may dead-lock due to starvation if the default dispatcher is
  overloaded. To avoid problems, do not call this method from within actors or
  futures which run on the default dispatcher.

Actors are automatically started asynchronously when created.
When you create the ``Actor`` then it will automatically call the ``preStart``
callback method on the ``Actor`` trait. This is an excellent place to
add initialization code for the actor.

.. code-block:: scala

  override def preStart() = {
    ... // initialization code
  }

Creating Actors with non-default constructor
--------------------------------------------

If your Actor has a constructor that takes parameters then you can't create it
using ``actorOf(Props[TYPE])``. Instead you can use a variant of ``actorOf`` that takes
a call-by-name block in which you can create the Actor in any way you like.

Here is an example:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#creating-constructor


Props
-----

``Props`` is a configuration class to specify options for the creation
of actors. Here are some examples on how to create a ``Props`` instance.

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#creating-props-config


Creating Actors with Props
--------------------------

Actors are created by passing in a ``Props`` instance into the ``actorOf`` factory method.

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#creating-props


Creating Actors using anonymous classes
---------------------------------------

When spawning actors for specific sub-tasks from within an actor, it may be convenient to include the code to be executed directly in place, using an anonymous class.

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#anonymous-actor

.. warning::

  In this case you need to carefully avoid closing over the containing actor’s
  reference, i.e. do not call methods on the enclosing actor from within the
  anonymous Actor class. This would break the actor encapsulation and may
  introduce synchronization bugs and race conditions because the other actor’s
  code will be scheduled concurrently to the enclosing actor. Unfortunately
  there is not yet a way to detect these illegal accesses at compile time.
  See also: :ref:`jmm-shared-state`


Actor API
=========

The :class:`Actor` trait defines only one abstract method, the above mentioned
:meth:`receive`, which implements the behavior of the actor.

If the current actor behavior does not match a received message,
:meth:`unhandled` is called, which by default publishes an
``akka.actor.UnhandledMessage(message, sender, recipient)`` on the actor
system’s event stream (set configuration item
``akka.event-handler-startup-timeout`` to ``true`` to have them converted into
actual Debug messages)

In addition, it offers:

* :obj:`self` reference to the :class:`ActorRef` of the actor
* :obj:`sender` reference sender Actor of the last received message, typically used as described in :ref:`Actor.Reply`
* :obj:`supervisorStrategy` user overridable definition the strategy to use for supervising child actors
* :obj:`context` exposes contextual information for the actor and the current message, such as:

  * factory methods to create child actors (:meth:`actorOf`)
  * system that the actor belongs to
  * parent supervisor
  * supervised children
  * lifecycle monitoring
  * hotswap behavior stack as described in :ref:`Actor.HotSwap`

You can import the members in the :obj:`context` to avoid prefixing access with ``context.``

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#import-context

The remaining visible methods are user-overridable life-cycle hooks which are
described in the following::

  def preStart() {}
  def preRestart(reason: Throwable, message: Option[Any]) {
    context.children foreach (context.stop(_))
    postStop()
  }
  def postRestart(reason: Throwable) { preStart() }
  def postStop() {}

The implementations shown above are the defaults provided by the :class:`Actor`
trait.

.. _deathwatch-scala:

Lifecycle Monitoring aka DeathWatch
-----------------------------------

In order to be notified when another actor terminates (i.e. stops permanently,
not temporary failure and restart), an actor may register itself for reception
of the :class:`Terminated` message dispatched by the other actor upon
termination (see `Stopping Actors`_). This service is provided by the
:class:`DeathWatch` component of the actor system.

Registering a monitor is easy:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#watch

It should be noted that the :class:`Terminated` message is generated
independent of the order in which registration and termination occur.
Registering multiple times does not necessarily lead to multiple messages being
generated, but there is no guarantee that only exactly one such message is
received: if termination of the watched actor has generated and queued the
message, and another registration is done before this message has been
processed, then a second message will be queued, because registering for
monitoring of an already terminated actor leads to the immediate generation of
the :class:`Terminated` message.

It is also possible to deregister from watching another actor’s liveliness
using ``context.unwatch(target)``, but obviously this cannot guarantee
non-reception of the :class:`Terminated` message because that may already have
been queued.

Start Hook
----------

Right after starting the actor, its :meth:`preStart` method is invoked.

::

  override def preStart() {
    // registering with other actors
    someService ! Register(self)
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
mailbox is unaffected by the restart, so processing of messages will resume
after the :meth:`postRestart` hook returns. The message
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

As described in :ref:`addressing`, each actor has a unique logical path, which
is obtained by following the chain of actors from child to parent until
reaching the root of the actor system, and it has a physical path, which may
differ if the supervision chain includes any remote supervisors. These paths
are used by the system to look up actors, e.g. when a remote message is
received and the recipient is searched, but they are also useful more directly:
actors may look up other actors by specifying absolute or relative
paths—logical or physical—and receive back an :class:`ActorRef` with the
result::

  context.actorFor("/user/serviceA/aggregator") // will look up this absolute path
  context.actorFor("../joe")                    // will look up sibling beneath same supervisor

The supplied path is parsed as a :class:`java.net.URI`, which basically means
that it is split on ``/`` into path elements. If the path starts with ``/``, it
is absolute and the look-up starts at the root guardian (which is the parent of
``"/user"``); otherwise it starts at the current actor. If a path element equals
``..``, the look-up will take a step “up” towards the supervisor of the
currently traversed actor, otherwise it will step “down” to the named child.
It should be noted that the ``..`` in actor paths here always means the logical
structure, i.e. the supervisor.

If the path being looked up does not exist, a special actor reference is
returned which behaves like the actor system’s dead letter queue but retains
its identity (i.e. the path which was looked up).

Remote actor addresses may also be looked up, if remoting is enabled::

  context.actorFor("akka://app@otherhost:1234/user/serviceB")

These look-ups return a (possibly remote) actor reference immediately, so you
will have to send to it and await a reply in order to verify that ``serviceB``
is actually reachable and running. An example demonstrating actor look-up is
given in :ref:`remote-lookup-sample-scala`.

Messages and immutability
=========================

**IMPORTANT**: Messages can be any kind of object but have to be
immutable. Scala can’t enforce immutability (yet) so this has to be by
convention. Primitives like String, Int, Boolean are always immutable. Apart
from these the recommended approach is to use Scala case classes which are
immutable (if you don’t explicitly expose the state) and works great with
pattern matching at the receiver side.

Here is an example:

.. code-block:: scala

  // define the case class
  case class Register(user: User)

  // create a new case class message
  val message = Register(user)

Other good messages types are ``scala.Tuple2``, ``scala.List``, ``scala.Map``
which are all immutable and great for pattern matching.


Send messages
=============

Messages are sent to an Actor through one of the following methods.

* ``!`` means “fire-and-forget”, e.g. send a message asynchronously and return
  immediately. Also known as ``tell``.
* ``?`` sends a message asynchronously and returns a :class:`Future`
  representing a possible reply. Also known as ``ask``.

Message ordering is guaranteed on a per-sender basis.

.. note::

    There are performance implications of using ``ask`` since something needs to
    keep track of when it times out, there needs to be something that bridges
    a ``Promise`` into an ``ActorRef`` and it also needs to be reachable through
    remoting. So always prefer ``tell`` for performance, and only ``ask`` if you must.

Tell: Fire-forget
-----------------

This is the preferred way of sending messages. No blocking waiting for a
message. This gives the best concurrency and scalability characteristics.

.. code-block:: scala

  actor ! "hello"

If invoked from within an Actor, then the sending actor reference will be
implicitly passed along with the message and available to the receiving Actor
in its ``sender: ActorRef`` member field. The target actor can use this
to reply to the original sender, by using ``sender ! replyMsg``.

If invoked from an instance that is **not** an Actor the sender will be
:obj:`deadLetters` actor reference by default.

Ask: Send-And-Receive-Future
----------------------------

The ``ask`` pattern involves actors as well as futures, hence it is offered as
a use pattern rather than a method on :class:`ActorRef`:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#ask-pipeTo

This example demonstrates ``ask`` together with the ``pipeTo`` pattern on
futures, because this is likely to be a common combination. Please note that
all of the above is completely non-blocking and asynchronous: ``ask`` produces
a :class:`Future`, three of which are composed into a new future using the
for-comprehension and then ``pipeTo`` installs an ``onComplete``-handler on the
future to effect the submission of the aggregated :class:`Result` to another
actor.

Using ``ask`` will send a message to the receiving Actor as with ``tell``, and
the receiving actor must reply with ``sender ! reply`` in order to complete the
returned :class:`Future` with a value. The ``ask`` operation involves creating
an internal actor for handling this reply, which needs to have a timeout after
which it is destroyed in order not to leak resources; see more below.

To complete the future with an exception you need send a Failure message to the sender.
This is *not done automatically* when an actor throws an exception while processing a
message.

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#reply-exception

If the actor does not complete the future, it will expire after the timeout
period, completing it with an :class:`AskTimeoutException`.  The timeout is
taken from one of the following locations in order of precedence:

1. explicitly given timeout as in:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#using-explicit-timeout

2. implicit argument of type :class:`akka.util.Timeout`, e.g.

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#using-implicit-timeout

See :ref:`futures-scala` for more information on how to await or query a
future.

The ``onComplete``, ``onResult``, or ``onTimeout`` methods of the ``Future`` can be
used to register a callback to get a notification when the Future completes.
Gives you a way to avoid blocking.

.. warning::

  When using future callbacks, such as ``onComplete``, ``onSuccess``, and ``onFailure``,
  inside actors you need to carefully avoid closing over
  the containing actor’s reference, i.e. do not call methods or access mutable state
  on the enclosing actor from within the callback. This would break the actor
  encapsulation and may introduce synchronization bugs and race conditions because
  the callback will be scheduled concurrently to the enclosing actor. Unfortunately
  there is not yet a way to detect these illegal accesses at compile time.
  See also: :ref:`jmm-shared-state`

Forward message
---------------

You can forward a message from one actor to another. This means that the
original sender address/reference is maintained even though the message is going
through a 'mediator'. This can be useful when writing actors that work as
routers, load-balancers, replicators etc.

.. code-block:: scala

  myActor.forward(message)


Receive messages
================

An Actor has to implement the ``receive`` method to receive messages:

.. code-block:: scala

  protected def receive: PartialFunction[Any, Unit]

Note: Akka has an alias to the ``PartialFunction[Any, Unit]`` type called
``Receive`` (``akka.actor.Actor.Receive``), so you can use this type instead for
clarity. But most often you don't need to spell it out.

This method should return a ``PartialFunction``, e.g. a ‘match/case’ clause in
which the message can be matched against the different case clauses using Scala
pattern matching. Here is an example:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala
   :include: imports1,my-actor


.. _Actor.Reply:

Reply to messages
=================

If you want to have a handle for replying to a message, you can use
``sender``, which gives you an ActorRef. You can reply by sending to
that ActorRef with ``sender ! replyMsg``. You can also store the ActorRef
for replying later, or passing on to other actors. If there is no sender (a
message was sent without an actor or future context) then the sender
defaults to a 'dead-letter' actor ref.

.. code-block:: scala

  case request =>
    val result = process(request)
    sender ! result       // will have dead-letter actor as default

Initial receive timeout
=======================

A timeout mechanism can be used to receive a message when no initial message is
received within a certain time. To receive this timeout you have to set the
``receiveTimeout`` property and declare a case handing the ReceiveTimeout
object.

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#receive-timeout

.. _stopping-actors-scala:

Stopping actors
===============

Actors are stopped by invoking the :meth:`stop` method of a ``ActorRefFactory``,
i.e. ``ActorContext`` or ``ActorSystem``. Typically the context is used for stopping
child actors and the system for stopping top level actors. The actual termination of
the actor is performed asynchronously, i.e. :meth:`stop` may return before the actor is
stopped.

Processing of the current message, if any, will continue before the actor is stopped,
but additional messages in the mailbox will not be processed. By default these
messages are sent to the :obj:`deadLetters` of the :obj:`ActorSystem`, but that
depends on the mailbox implementation.

Termination of an actor proceeds in two steps: first the actor suspends its
mailbox processing and sends a stop command to all its children, then it keeps
processing the termination messages from its children until the last one is
gone, finally terminating itself (invoking :meth:`postStop`, dumping mailbox,
publishing :class:`Terminated` on the :ref:`DeathWatch <deathwatch-scala>`, telling
its supervisor). This procedure ensures that actor system sub-trees terminate
in an orderly fashion, propagating the stop command to the leaves and
collecting their confirmation back to the stopped supervisor. If one of the
actors does not respond (i.e. processing a message for extended periods of time
and therefore not receiving the stop command), this whole process will be
stuck.

Upon :meth:`ActorSystem.shutdown()`, the system guardian actors will be
stopped, and the aforementioned process will ensure proper termination of the
whole system.

The :meth:`postStop()` hook is invoked after an actor is fully stopped. This
enables cleaning up of resources:

.. code-block:: scala

  override def postStop() = {
    // close some file or database connection
  }

.. note::

  Since stopping an actor is asynchronous, you cannot immediately reuse the
  name of the child you just stopped; this will result in an
  :class:`InvalidActorNameException`. Instead, :meth:`watch()` the terminating
  actor and create its replacement in response to the :class:`Terminated`
  message which will eventually arrive.

PoisonPill
----------

You can also send an actor the ``akka.actor.PoisonPill`` message, which will
stop the actor when the message is processed. ``PoisonPill`` is enqueued as
ordinary messages and will be handled after messages that were already queued
in the mailbox.

Graceful Stop
-------------

:meth:`gracefulStop` is useful if you need to wait for termination or compose ordered
termination of several actors:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#gracefulStop

When ``gracefulStop()`` returns successfully, the actor’s ``postStop()`` hook
will have been executed: there exists a happens-before edge between the end of
``postStop()`` and the return of ``gracefulStop()``.

.. warning::

  Keep in mind that an actor stopping and its name being deregistered are
  separate events which happen asynchronously from each other. Therefore it may
  be that you will find the name still in use after ``gracefulStop()``
  returned. In order to guarantee proper deregistration, only reuse names from
  within a supervisor you control and only in response to a :class:`Terminated`
  message, i.e. not for top-level actors.

.. _Actor.HotSwap:

Become/Unbecome
===============

Upgrade
-------

Akka supports hotswapping the Actor’s message loop (e.g. its implementation) at
runtime: Invoke the ``context.become`` method from within the Actor.

Become takes a ``PartialFunction[Any, Unit]`` that implements
the new message handler. The hotswapped code is kept in a Stack which can be
pushed and popped.

.. warning::

  Please note that the actor will revert to its original behavior when restarted by its Supervisor.

To hotswap the Actor behavior using ``become``:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#hot-swap-actor

The ``become`` method is useful for many different things, but a particular nice
example of it is in example where it is used to implement a Finite State Machine
(FSM): `Dining Hakkers`_.

.. _Dining Hakkers: http://github.com/akka/akka/blob/master/akka-samples/akka-sample-fsm/src/main/scala/DiningHakkersOnBecome.scala

Here is another little cute example of ``become`` and ``unbecome`` in action:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#swapper

Encoding Scala Actors nested receives without accidentally leaking memory
-------------------------------------------------------------------------

See this `Unnested receive example <https://github.com/akka/akka/blob/master/akka-docs/scala/code/akka/docs/actor/UnnestedReceives.scala>`_.


Downgrade
---------

Since the hotswapped code is pushed to a Stack you can downgrade the code as
well, all you need to do is to: Invoke the ``context.unbecome`` method from within the Actor.

This will pop the Stack and replace the Actor's implementation with the
``PartialFunction[Any, Unit]`` that is at the top of the Stack.

Here's how you use the ``unbecome`` method:

.. code-block:: scala

  def receive = {
    case "revert" => context.unbecome()
  }


Killing an Actor
================

You can kill an actor by sending a ``Kill`` message. This will restart the actor
through regular supervisor semantics.

Use it like this:

.. code-block:: scala

  // kill the actor called 'victim'
  victim ! Kill


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


Extending Actors using whenBecoming and PartialFunction chaining
===============================================================

You can create "mixin" traits or abstract classes using the
``whenBecoming`` method on ``Actor``. This method modifies the
standard actor behavior as defined by ``receive`` or ``become``.
To allow multiple traits to be mixed in to one actor, when you
override ``whenBecoming`` you should always chain up and allow
supertypes to run their ``whenBecoming`` as well.

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#receive-whenBecoming

Multiple traits that implement ``whenBecoming`` in this way can be
mixed in to the same concrete class. The concrete class need not
do anything special, it implements ``receive`` as usual.

``PartialFunction.orElse`` chaining can also be used for more
complex scenarios, like dynamic runtime registration of handlers:

.. includecode:: code/akka/docs/actor/ActorDocSpec.scala#receive-orElse
