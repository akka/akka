.. _migration-2.0:

################################
 Migration Guide 1.3.x to 2.0.x
################################

.. sidebar:: Contents

   .. contents::
      :local:
      :depth: 3

The 2.0 release contains several new features which require source-level
changes in client code. This API cleanup is planned to be the last one for a
significant amount of time.

Migration Kit
=============

Nobody likes a big refactoring that takes several days to complete until
anything is able to run again. Therefore we provide a migration kit that
makes it possible to do the migration changes in smaller steps.

The migration kit only covers the most common usage of Akka. It is not intended
as a final solution. The whole migration kit is marked as deprecated and will
be removed in Akka 2.1.

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

Actors
------

Creating and starting actors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

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
^^^^^^^^^^^^^^^

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

  system.shutdown() // from outside of this system
  context.system.shutdown() // from inside any actor

Documentation:

 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

Identifying Actors
^^^^^^^^^^^^^^^^^^

In v1.3 actors have ``uuid`` and ``id`` field. In v2.0 each actor has a unique logical ``path``.

The ``ActorRegistry`` has been replaced by actor paths and lookup with
``actorFor`` in ``ActorRefProvider`` (``ActorSystem`` or ``ActorContext``). It
is no longer possible to obtain references to all actors being implemented by a
certain class (the reason being that this property is not known yet when an
:class:`ActorRef` is created because instantiation of the actor itself is
asynchronous).

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
^^^^^^^^^^^^^^^^^

``self.channel`` has been replaced with unified reply mechanism using ``sender`` (Scala)
or ``getSender()`` (Java). This works for both tell (!) and ask (?). Sending to
an actor reference never throws an exception, hence :meth:`tryTell` and
:meth:`tryReply` are removed.

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
^^^^^^^^^^^^^^^^^^

The mechanism for collecting an actor’s reply in a :class:`Future` has been
reworked for better location transparency: it uses an actor under the hood.
This actor needs to be disposable by the garbage collector in case no reply is
ever received, and the decision is based upon a timeout. This timeout
determines when the actor will stop itself and hence closes the window for a
reply to be received; it is independent of the timeout applied when awaiting
completion of the :class:`Future`, however, the actor will complete the
:class:`Future` with an :class:`AskTimeoutException` when it stops itself.

Since there is no good library default value for the ask-timeout, specification
of a timeout is required for all usages as shown below.

Also, since the ``ask`` feature is coupling futures and actors, it is no longer
offered on the :class:`ActorRef` itself, but instead as a use pattern to be
imported. While Scala’s implicit conversions enable transparent replacement,
Java code will have to be changed by more than just adding an import statement.

v1.3::

  actorRef ? message // Scala
  actorRef.ask(message, timeout); // Java

v2.0 (Scala)::

  import akka.pattern.ask

  implicit val timeout: Timeout = ...
  actorRef ? message              // uses implicit timeout
  actorRef ask message            // uses implicit timeout
  actorRef.ask(message)(timeout)  // uses explicit timeout
  ask(actorRef, message)          // uses implicit timeout
  ask(actorRef, message)(timeout) // uses explicit timeout

v2.0 (Java)::

  import akka.pattern.Patterns;

  Patterns.ask(actorRef, message, timeout)

Documentation:

 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

``ActorRef.?(msg, timeout)``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This method has a dangerous overlap with ``ActorRef.?(msg)(implicit timeout)``
due to the fact that Scala allows to pass a :class:`Tuple` in place of the
message without requiring extra parentheses::

  actor ? (1, "hallo") // will send a tuple
  actor ? (1, Timeout()) // will send 1 with an explicit timeout

To remove this ambiguity, the latter variant is removed in version 2.0. If you
were using it before, it will now send tuples where that is not desired. In
order to correct all places in the code where this happens, simply import
``akka.migration.ask`` instead of ``akka.pattern.ask`` to obtain a variant
which will give deprecation warnings where the old method signature is used::

  import akka.migration.ask

  actor ? (1, Timeout(2 seconds)) // will give deprecation warning

ActorPool
^^^^^^^^^

The ActorPool has been replaced by dynamically resizable routers.

Documentation:

 * :ref:`routing-scala`
 * :ref:`routing-java`

``UntypedActor.getContext()`` (Java API only)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

``getContext()`` in the Java API for UntypedActor is renamed to
``getSelf()``.

v1.3::

  actorRef.tell("Hello", getContext());

v2.0::

  actorRef.tell("Hello", getSelf());

Documentation:

 * :ref:`untyped-actors-java`

Configuration
^^^^^^^^^^^^^

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
^^^^^^^

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

  val log = Logging(context.system, this) // will include system name in message source
  val log = Logging(system.eventStream, getClass.getName) // will not include system name
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
^^^^^^^^^

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
^^^^^^^^^^^

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

*Supervisor Strategy*

v1.3::

  val supervisor = Supervisor(
    SupervisorConfig(
      OneForOneStrategy(List(classOf[Exception]), 3, 1000),
      Supervise(
        actorOf[MyActor1],
        Permanent) ::
      Supervise(
        actorOf[MyActor2],
        Permanent) ::
      Nil))

v2.0::

  class MyActor extends Actor {
    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
        case _: ArithmeticException      ⇒ Resume
        case _: NullPointerException     ⇒ Restart
        case _: IllegalArgumentException ⇒ Stop
        case _: Exception                ⇒ Escalate
      }

    def receive = {
      case x =>
    }
  }

Documentation:

 * :ref:`supervision`
 * :ref:`fault-tolerance-java`
 * :ref:`fault-tolerance-scala`
 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

Dispatchers
^^^^^^^^^^^

Dispatchers are defined in configuration instead of in code.

v1.3::

  // in code
  val myDispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher(name)
    .withNewThreadPoolWithLinkedBlockingQueueWithCapacity(100)
    .setCorePoolSize(16)
    .setMaxPoolSize(128)
    .setKeepAliveTimeInMillis(60000)
    .build

v2.0::

  // in config
  my-dispatcher {
    type = Dispatcher
    core-pool-size-factor = 8.0
    max-pool-size-factor  = 16.0
    mailbox-capacity = 100
  }

The dispatcher is assigned to the actor in a different way.

v1.3::

  actorRef.dispatcher = MyGlobals.myDispatcher
  self.dispatcher = MyGlobals.myDispatcher

v2.0::

  val myActor = system.actorOf(Props[MyActor].withDispatcher("my-dispatcher"), "myactor")

Documentation:

 * :ref:`dispatchers-java`
 * :ref:`dispatchers-scala`

Spawn
^^^^^

``spawn`` has been removed and should be replaced by creating a :class:`Future`. Be careful to not
access any shared mutable state closed over by the body.

Scala::

  Future { doSomething() } // will be executed asynchronously

Java::

  Futures.future<String>(new Callable<String>() {
    public String call() {
      doSomething();
    }
  }, executionContext);

Documentation:

  * :ref:`futures-scala`
  * :ref:`futures-java`
  * :ref:`jmm`

HotSwap
^^^^^^^

In v2.0 ``become`` and ``unbecome`` metods are located in ``ActorContext``, i.e. ``context.become`` and ``context.unbecome``.

The special ``HotSwap`` and ``RevertHotswap`` messages in v1.3 has been removed. Similar can be
implemented with your own message and using ``context.become`` and ``context.unbecome``
in the actor receiving the message. The rationale is that being able to replace
any actor’s behavior generically is not a good idea because actor implementors
would have no way to defend against that; hence the change to lay it into the
hands of the actor itself.

 * :ref:`actors-scala`
 * :ref:`untyped-actors-java`

STM
---

In Akka v2.0 `ScalaSTM`_ is used rather than Multiverse.

.. _ScalaSTM: http://nbronson.github.com/scala-stm/

Agent and Transactor have been ported to ScalaSTM. The API's for Agent and
Transactor are basically the same, other than integration with ScalaSTM. See:

 * :ref:`agents-scala`
 * :ref:`agents-java`
 * :ref:`transactors-scala`
 * :ref:`transactors-java`

Imports
^^^^^^^

Scala
~~~~~

To use ScalaSTM the import from Scala is::

  import scala.concurrent.stm._

Java
~~~~

For Java there is a special helper object with Java-friendly methods::

  import scala.concurrent.stm.japi.Stm;

These methods can also be statically imported::

  import static scala.concurrent.stm.japi.Stm.*;

Other imports that are needed are in the stm package, particularly ``Ref``::

  import scala.concurrent.stm.Ref;

Transactions
^^^^^^^^^^^^

Scala
~~~~~

Both v1.3 and v2.0 provide an ``atomic`` block, however, the ScalaSTM ``atomic``
is a function from ``InTxn`` to return type.

v1.3::

  atomic {
    // do something in transaction
  }

v2.0::

  atomic { implicit txn =>
    // do something in transaction
  }

Note that in ScalaSTM the ``InTxn`` in the atomic function is usually marked as
implicit as transactional references require an implicit ``InTxn`` on all
methods. That is, the transaction is statically required and it is a
compile-time warning to use a reference without a transaction. There is also a
``Ref.View`` for operations without requiring an ``InTxn`` statically. See below
for more information.

Java
~~~~

In the ScalaSTM Java API helpers there are atomic methods which accept
``java.lang.Runnable`` and ``java.util.concurrent.Callable``.

v1.3::

  new Atomic() {
      public Object atomically() {
          // in transaction
          return null;
      }
  }.execute();

  SomeObject result = new Atomic<SomeObject>() {
      public SomeObject atomically() {
          // in transaction
          return ...;
      }
  }.execute();

v2.0::

  import static scala.concurrent.stm.japi.Stm.atomic;
  import java.util.concurrent.Callable;

  atomic(new Runnable() {
      public void run() {
          // in transaction
      }
  });

  SomeObject result = atomic(new Callable<SomeObject>() {
      public SomeObject call() {
          // in transaction
          return ...;
      }
  });

Ref
^^^

Scala
~~~~~

Other than the import, creating a Ref is basically identical between Akka STM in
v1.3 and ScalaSTM used in v2.0.

v1.3::

  val ref = Ref(0)

v2.0::

  val ref = Ref(0)

The API for Ref is similar. For example:

v1.3::

  ref.get // get current value
  ref()   // same as get

  ref.set(1)  // set to new value, return old value
  ref() = 1   // same as set
  ref.swap(2) // same as set

  ref alter { _ + 1 } // apply a function, return new value

v2.0::

  ref.get // get current value
  ref()   // same as get

  ref.set(1)  // set to new value, return nothing
  ref() = 1   // same as set
  ref.swap(2) // set and return old value

  ref transform { _ + 1 } // apply function, return nothing

  ref transformIfDefined { case 1 => 2 } // apply partial function if defined

Ref.View
^^^^^^^^

In v1.3 using a ``Ref`` method outside of a transaction would automatically
create a single-operation transaction. In v2.0 (in ScalaSTM) there is a
``Ref.View`` which provides methods without requiring a current
transaction.

Scala
~~~~~

The ``Ref.View`` can be accessed with the ``single`` method::

  ref.single() // returns current value
  ref.single() = 1 // set new value

  // with atomic this would be:

  atomic { implicit t => ref() }
  atomic { implicit t => ref() = 1 }

Java
~~~~

As ``Ref.View`` in ScalaSTM does not require implicit transactions, this is more
easily used from Java. ``Ref`` could be used, but requires explicit threading of
transactions. There are helper methods in ``japi.Stm`` for creating ``Ref.View``
references.

v1.3::

  Ref<Integer> ref = new Ref<Integer>(0);

v2.0::

  Ref.View<Integer> ref = Stm.newRef(0);

The ``set`` and ``get`` methods work the same way for both versions.

v1.3::

  ref.get();  // get current value
  ref.set(1); // set new value

v2.0::

  ref.get();  // get current value
  ref.set(1); // set new value

There are also ``transform``, ``getAndTransform``, and ``transformAndGet``
methods in ``japi.Stm`` which accept ``scala.runtime.AbstractFunction1``.

There are ``increment`` helper methods for ``Ref.View<Integer>`` and
``Ref.View<Long>`` references.

Transaction lifecycle callbacks
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Scala
~~~~~

It is also possible to hook into the transaction lifecycle in ScalaSTM. See the
ScalaSTM documentation for the full range of possibilities.

v1.3::

  atomic {
    deferred {
      // executes when transaction commits
    }
    compensating {
      // executes when transaction aborts
    }
  }

v2.0::

  atomic { implicit txn =>
    txn.afterCommit { txnStatus =>
      // executes when transaction commits
    }
    txn.afterRollback { txnStatus =>
      // executes when transaction rolls back
    }
  }

Java
~~~~

Rather than using the ``deferred`` and ``compensating`` methods in
``akka.stm.StmUtils``, use the ``afterCommit`` and ``afterRollback`` methods in
``scala.concurrent.stm.japi.Stm``, which behave in the same way and accept
``Runnable``.

Transactional Datastructures
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In ScalaSTM see ``TMap``, ``TSet``, and ``TArray`` for transactional
datastructures.

There are helper methods for creating these from Java in ``japi.Stm``:
``newTMap``, ``newTSet``, and ``newTArray``. These datastructures implement the
``scala.collection`` interfaces and can also be used from Java with Scala's
``JavaConversions``. There are helper methods that apply the conversions,
returning ``java.util`` ``Map``, ``Set``, and ``List``: ``newMap``, ``newSet``,
and ``newList``.


More to be written
------------------

* Futures
* TypedActors
* Routing
* Remoting
