.. _migration-2.0:

################################
 Migration Guide 1.3.x to 2.0.x
################################

.. sidebar:: Contents

   .. contents:: :local:

Actors
======

The 2.0 release contains several new features which require source-level
changes in client code. This API cleanup is planned to be the last one for a
significant amount of time.

Detailed migration guide will be written.

Migration Kit
=============

Nobody likes a big refactoring that takes several days to complete until
anything is able to run again. Therefore we provide a migration kit that
makes it possible to do the migration changes in smaller steps.

The migration kit only covers the most common usage of Akka. It is not intended
as a final solution. The whole migration kit is deprecated and will be removed in
Akka 2.1.

The migration kit is provided in separate jar files. Add the following dependency::

  "com.typesafe.akka" % "akka-actor-migration" % "2.0-SNAPSHOT"

The first step of the migration is to do some trivial replacements.
Search and replace the following (be careful with the non qualified names):

==================================== ====================================
Search                               Replace with
==================================== ====================================
``akka.actor.Actor``                 ``akka.actor.OldActor``
``extends Actor``                    ``extends OldActor``
``akka.actor.Scheduler``             ``akka.actor.OldScheduler``
``Scheduler``                        ``OldScheduler``
``akka.event.EventHandler``          ``akka.event.OldEventHandler``
``EventHandler``                     ``OldEventHandler``
``akka.config.Config``               ``akka.config.OldConfig``
``Config``                           ``OldConfig``
==================================== ====================================

For Scala users the migration kit also contains some implicit conversions to be
able to use some old methods. These conversions are useful from tests or other
code used outside actors.

::

  import akka.migration._

Thereafter you need to fix compilation errors that are not handled by the migration
kit, such as:

* Definition of supervisors
* Definition of dispatchers
* ActorRegistry

When everything compiles you continue by replacing/removing the ``OldXxx`` classes
one-by-one from the migration kit with appropriate migration.

When using the migration kit there will be one global actor system, which loads
the configuration ``akka.conf`` from the same locations as in Akka 1.x.
This means that while you are using the migration kit you should not create your
own ``ActorSystem``, but instead use the ``akka.actor.GlobalActorSystem``.
In order to voluntarily exit the JVM you must ``shutdown`` the ``GlobalActorSystem``
Last task of the migration would be to create your own ``ActorSystem``.


Unordered Collection of Migration Items
=======================================

Creating and starting actors
----------------------------

Actors are created by passing in a ``Props`` instance into the actorOf factory method in
a ``ActorRefProvider``, which is the ``ActorSystem`` or ``ActorContext``.
Use the system to create top level actors. Use the context to
create actors from other actors. The difference is how the supervisor hierarchy is arranged.
When using the context the current actor will be supervisor of the created child actor.
When using the system it will be a top level actor, that is supervised by the system
(internal guardian actor).

``ActorRef.start()`` has been removed. Actors are now started automatically when created.
Remove all invocations of ``ActorRef.start()``.

v1.3::

  val myActor = Actor.actorOf[MyActor]
  myActor.start()

v2.0::

  // top level actor
  val firstActor = system.actorOf(Props[FirstActor], name = "first")

  // child actor
  class FirstActor extends Actor {
    val myActor = context.actorOf(Props[MyActor], name = "myactor")

Documentation:

 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

Stopping actors
---------------

``ActorRef.stop()`` has been moved. Use ``ActorSystem`` or ``ActorContext`` to stop actors.

v1.3::

   actorRef.stop()
   self.stop()
   actorRef ! PoisonPill

v2.0::

  context.stop(someChild)
  context.stop(self)
  system.stop(actorRef)
  actorRef ! PoisonPill

*Stop all actors*

v1.3::

  ActorRegistry.shutdownAll()

v2.0::

  system.shutdown()

Documentation:

 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

Identifying Actors
------------------

In v1.3 actors have ``uuid`` and ``id`` field. In v2.0 each actor has a unique logical ``path``.

The ``ActorRegistry`` has been replaced by actor paths and lookup with
``actorFor`` in ``ActorRefProvider`` (``ActorSystem`` or ``ActorContext``).

v1.3::

  val actor =  Actor.registry.actorFor(uuid)
  val actors =  Actor.registry.actorsFor(id)

v2.0::

  val actor = context.actorFor("/user/serviceA/aggregator")

Documentation:

 * :ref:`addressing`
 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

Reply to messages
-----------------

``self.channel`` has been replaced with unified reply mechanism using ``sender`` (Scala)
or ``getSender()`` (Java). This works for both tell (!) and ask (?).

v1.3::

  self.channel ! result
  self.channel tryTell result
  self.reply(result)
  self.tryReply(result)

v2.0::

  sender ! result

Documentation:

 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

``ActorRef.ask()``
------------------

The mechanism for collecting an actor’s reply in a :class:`Future` has been
reworked for better location transparency: it uses an actor under the hood.
This actor needs to be disposable by the garbage collector in case no reply is
ever received, and the decision is based upon a timeout. This timeout
determines when the actor will stop itself and hence closes the window for a
reply to be received; it is independent of the timeout applied when awaiting
completion of the :class:`Future`, however, the actor will complete the
:class:`Future` with an :class:`AskTimeoutException` when it stops itself.

Documentation:

 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

ActorPool
---------

The ActorPool has been replaced by dynamically resizable routers.

Documentation:

 * :ref:`routing-scala`
 * :ref:`routing-java`

``UntypedActor.getContext()`` (Java API only)
---------------------------------------------

``getContext()`` in the Java API for UntypedActor is renamed to
``getSelf()``.

v1.3::

  actorRef.tell("Hello", getContext());

v2.0::

  actorRef.tell("Hello", getSelf());

Documentation:

 * :ref:`untyped-actors-java`

Configuration
-------------

A new, more powerful, configuration utility has been implemented. The format of the
configuration file is very similar to the format in v1.3. In addition it also supports
configuration files in json and properties format. The syntax is described in the
`HOCON <https://github.com/typesafehub/config/blob/master/HOCON.md>`_ specification.

v1.3::

  include "other.conf"

  akka {
    event-handler-level = "DEBUG"
  }

v2.0::

  include "other"

  akka {
    loglevel = "DEBUG"
  }

In v1.3 the default name of the configuration file was ``akka.conf``.
In v2.0 the default name is ``application.conf``. It is still loaded from classpath
or can be specified with java System properties (``-D`` command line arguments).

v1.3::

  -Dakka.config=<file path to configuration file>
  -Dakka.output.config.source=on

v2.0::

  -Dconfig.file=<file path to configuration file>
  -Dakka.logConfigOnStart=on


Several configuration properties have been changed, such as:

  * ``akka.event-handler-level`` => ``akka.loglevel``
  * dispatcher ``type`` values are changed
  * ``akka.actor.throughput`` => ``akka.actor.default-dispatcher.throughput``
  * ``akka.remote.layer`` => ``akka.remote.transport``
  * the global ``time-unit`` property is removed, all durations are specified with duration unit in the property value, ``timeout = 5s``

Verify used configuration properties against the reference :ref:`configuration`.

Documentation:

 * :ref:`configuration`

Logging
-------

EventHandler API has been replaced by LoggingAdapter, which publish log messages
to the event bus. You can still plugin your own actor as event listener with the
``akka.event-handlers`` configuration property.

v1.3::

  EventHandler.error(exception, this, message)
  EventHandler.warning(this, message)
  EventHandler.info(this, message)
  EventHandler.debug(this, message)
  EventHandler.debug(this, "Processing took %s ms".format(duration))

v2.0::

  import akka.event.Logging

  val log = Logging(context.system, this)
  log.error(exception, message)
  log.warning(message)
  log.info(message)
  log.debug(message)
  log.debug("Processing took {} ms", duration)

Documentation:

  * :ref:`logging-scala`
  * :ref:`logging-java`
  * :ref:`event-bus-scala`
  * :ref:`event-bus-java`


Scheduler
---------

The functionality of the scheduler is identical, but the API is slightly adjusted.

v1.3::

  //Schedules to send the "foo"-message to the testActor after 50ms
  Scheduler.scheduleOnce(testActor, "foo", 50L, TimeUnit.MILLISECONDS)

  // Schedules periodic send of "foo"-message to the testActor after 1s inital delay,
  // and then with 200ms between successive sends
  Scheduler.schedule(testActor, "foo", 1000L, 200L, TimeUnit.MILLISECONDS)

  // Schedules a function to be executed (send the current time) to the testActor after 50ms
  Scheduler.scheduleOnce({testActor ! System.currentTimeMillis}, 50L, TimeUnit.MILLISECONDS)

v2.0::

  //Schedules to send the "foo"-message to the testActor after 50ms
  system.scheduler.scheduleOnce(50 milliseconds, testActor, "foo")

  // Schedules periodic send of "foo"-message to the testActor after 1s inital delay,
  // and then with 200ms between successive sends
  system.scheduler.schedule(1 second, 200 milliseconds, testActor, "foo")

  // Schedules a function to be executed (send the current time) to the testActor after 50ms
  system.scheduler.scheduleOnce(50 milliseconds) {
    testActor ! System.currentTimeMillis
  }


The internal implementation of the scheduler is changed from
``java.util.concurrent.ScheduledExecutorService`` to a variant of
``org.jboss.netty.util.HashedWheelTimer``.

Documentation:

  * :ref:`scheduler-scala`
  * :ref:`scheduler-java`

Supervision
-----------

Akka v2.0 implements parental supervision. Actors can only be created by other actors — where the top-level
actor is provided by the library — and each created actor is supervised by its parent.
In contrast to the special supervision relationship between parent and child, each actor may monitor any
other actor for termination.

v1.3::

  self.link(actorRef)
  self.unlink(actorRef)

v2.0::

  class WatchActor extends Actor {
    val actorRef = ...
    // Terminated message will be delivered when the actorRef actor
    // is stopped
    context.watch(actorRef)

    val supervisedChild = context.actorOf(Props[ChildActor])

    def receive = {
      case Terminated(`actorRef`) ⇒ ...
    }
  }

Note that ``link`` in v1.3 established a supervision relation, which ``watch`` doesn't.
``watch`` is only a way to get notification, ``Terminated`` message, when the monitored
actor has been stopped.

*Refererence to the supervisor*

v1.3::

  self.supervisor

v2.0::

  context.parent

*Fault handling strategy*

v1.3::

  val supervisor = Supervisor(
    SupervisorConfig(
      AllForOneStrategy(List(classOf[Exception]), 3, 1000),
      Supervise(
        actorOf[MyActor1],
        Permanent) ::
      Supervise(
        actorOf[MyActor2],
        Permanent) ::
      Nil))

v2.0::

  val strategy = OneForOneStrategy({
    case _: ArithmeticException      ⇒ Resume
    case _: NullPointerException     ⇒ Restart
    case _: IllegalArgumentException ⇒ Stop
    case _: Exception                ⇒ Escalate
  }: Decider, maxNrOfRetries = Some(10), withinTimeRange = Some(60000))

  val supervisor = system.actorOf(Props[Supervisor].withFaultHandler(strategy), "supervisor")

Documentation:

 * :ref:`supervision`
 * :ref:`fault-tolerance-java`
 * :ref:`fault-tolerance-scala`
 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

Spawn
-----

``spawn`` has been removed and can be implemented like this, if needed. Be careful to not
access any shared mutable state closed over by the body.

::

  def spawn(body: ⇒ Unit) {
    system.actorOf(Props(ctx ⇒ { case "go" ⇒ try body finally ctx.stop(ctx.self) })) ! "go"
  }

Documentation:

  * :ref:`jmm`

HotSwap
-------

In v2.0 ``become`` and ``unbecome`` metods are located in ``ActorContext``, i.e. ``context.become`` and ``context.unbecome``.

The special ``HotSwap`` and ``RevertHotswap`` messages in v1.3 has been removed. Similar can be
implemented with your own message and using ``context.become`` and ``context.unbecome``
in the actor receiving the message.

 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

More to be written
------------------

* Futures
* Dispatchers
* STM
* TypedActors
* Routing
* Remoting
* ...?