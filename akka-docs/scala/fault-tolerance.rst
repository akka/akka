.. _fault-tolerance-scala:

Fault Tolerance Through Supervisor Hierarchies (Scala)
======================================================

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **SOLID**

The "let it crash" approach to fault/error handling, implemented by linking actors, is very different to what Java and most non-concurrency oriented languages/frameworks have adopted. It's a way of dealing with failure that is designed for concurrent and distributed systems.

Concurrency
-----------

Throwing an exception in concurrent code (let's assume we are using non-linked actors), will just simply blow up the thread that currently executes the actor.

- There is no way to find out that things went wrong (apart from inspecting the stack trace).
- There is nothing you can do about it.

Here actors provide a clean way of getting notification of the error and do something about it.

Linking actors also allow you to create sets of actors where you can be sure that either:

- All are dead
- None are dead

This is very useful when you have thousands of concurrent actors. Some actors might have implicit dependencies and together implement a service, computation, user session etc.

It encourages non-defensive programming. Don't try to prevent things from go wrong, because they will, whether you want it or not. Instead; expect failure as a natural state in the life-cycle of your app, crash early and let someone else (that sees the whole picture), deal with it.

Distributed actors
------------------

You can't build a fault-tolerant system with just one single box - you need at least two. Also, you (usually) need to know if one box is down and/or the service you are talking to on the other box is down. Here actor supervision/linking is a critical tool for not only monitoring the health of remote services, but to actually manage the service, do something about the problem if the actor or node is down. Such as restarting actors on the same node or on another node.

In short, it is a very different way of thinking, but a way that is very useful (if not critical) to building fault-tolerant highly concurrent and distributed applications, which is as valid if you are writing applications for the JVM or the Erlang VM (the origin of the idea of "let-it-crash" and actor supervision).

Supervision
-----------

Supervisor hierarchies originate from `Erlang's OTP framework <http://www.erlang.org/doc/design_principles/sup_princ.html#5>`_.

A supervisor is responsible for starting, stopping and monitoring its child processes. The basic idea of a supervisor is that it should keep its child processes alive by restarting them when necessary. This makes for a completely different view on how to write fault-tolerant servers. Instead of trying all things possible to prevent an error from happening, this approach embraces failure. It shifts the view to look at errors as something natural and something that **will** happen, instead of trying to prevent it; embraces it. Just "Let It Crash", since the components will be reset to a stable state and restarted upon failure.

Akka has two different restart strategies; All-For-One and One-For-One. Best explained using some pictures (referenced from `erlang.org <http://erlang.org>`_ ):

OneForOne
^^^^^^^^^

The OneForOne fault handler will restart only the component that has crashed.
`<image:http://www.erlang.org/doc/design_principles/sup4.gif>`_

AllForOne
^^^^^^^^^

The AllForOne fault handler will restart all the components that the supervisor is managing, including the one that have crashed. This strategy should be used when you have a certain set of components that are coupled in some way that if one is crashing they all need to be reset to a stable state before continuing.
`<image:http://www.erlang.org/doc/design_principles/sup5.gif>`_

Restart callbacks
^^^^^^^^^^^^^^^^^

There are two different callbacks that the Typed Actor and Actor can hook in to:

* Pre restart
* Post restart

These are called prior to and after the restart upon failure and can be used to clean up and reset/reinitialize state upon restart. This is important in order to reset the component failure and leave the component in a fresh and stable state before consuming further messages.

Defining a supervisor's restart strategy
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Both the Typed Actor supervisor configuration and the Actor supervisor configuration take a 'FaultHandlingStrategy' instance which defines the fault management. The different strategies are:

* AllForOne
* OneForOne

These have the semantics outlined in the section above.

Here is an example of how to define a restart strategy:

.. code-block:: scala

  AllForOnePermanentStrategy( //FaultHandlingStrategy; AllForOnePermanentStrategy or OneForOnePermanentStrategy
    List(classOf[Exception]), //What exceptions will be handled
    3,           // maximum number of restart retries
    5000        // within time in millis
  )

Defining actor life-cycle
^^^^^^^^^^^^^^^^^^^^^^^^^

The other common configuration element is the "LifeCycle' which defines the life-cycle. The supervised actor can define one of two different life-cycle configurations:

* Permanent: which means that the actor will always be restarted.
* Temporary: which means that the actor will **not** be restarted, but it will be shut down through the regular shutdown process so the 'postStop' callback function will called.

Here is an example of how to define the life-cycle:

.. code-block:: scala

  Permanent // means that the component will always be restarted
  Temporary // means that it will not be restarted, but it will be shut
            // down through the regular shutdown process so the 'postStop' hook will called

Supervising Actors
------------------

Declarative supervisor configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Actor's supervision can be declaratively defined by creating a "Supervisor' factory object. Here is an example:

.. code-block:: scala

  val supervisor = Supervisor(
    SupervisorConfig(
      AllForOnePermanentStrategy(List(classOf[Exception]), 3, 1000),
      Supervise(
        actorOf[MyActor1],
        Permanent) ::
      Supervise(
        actorOf[MyActor2],
        Permanent) ::
      Nil))

Supervisors created like this are implicitly instantiated and started.

To configure a handler function for when the actor underlying the supervisor receives a MaximumNumberOfRestartsWithinTimeRangeReached message, you can specify a function of type
(ActorRef, MaximumNumberOfRestartsWithinTimeRangeReached) => Unit when creating the SupervisorConfig. This handler will be called with the ActorRef of the supervisor and the
MaximumNumberOfRestartsWithinTimeRangeReached message.


.. code-block:: scala

  val handler = {
    (supervisor:ActorRef,max:MaximumNumberOfRestartsWithinTimeRangeReached) => EventHandler.notify(supervisor,max)
  }

  val supervisor = Supervisor(
    SupervisorConfig(
      AllForOnePermanentStrategy(List(classOf[Exception]), 3, 1000),
      Supervise(
        actorOf[MyActor1],
        Permanent) ::
      Supervise(
        actorOf[MyActor2],
        Permanent) ::
      Nil), handler)


You can link and unlink actors from a declaratively defined supervisor using the 'link' and 'unlink' methods:

.. code-block:: scala

  val supervisor = Supervisor(...)
  supervisor.link(..)
  supervisor.unlink(..)

You can also create declarative supervisors through the 'SupervisorFactory' factory object. Use this factory instead of the 'Supervisor' factory object if you want to control instantiation and starting of the Supervisor, if not then it is easier and better to use the 'Supervisor' factory object.

Example usage:

.. code-block:: scala

  val factory = SupervisorFactory(
    SupervisorConfig(
      OneForOnePermanentStrategy(List(classOf[Exception]), 3, 10),
      Supervise(
        myFirstActor,
        Permanent) ::
      Supervise(
        mySecondActor,
        Permanent) ::
      Nil))

Then create a new instance our Supervisor and start it up explicitly.

.. code-block:: scala

  val supervisor = factory.newInstance
  supervisor.start // start up all managed servers

Declaratively define actors as remote services
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can declaratively define an actor to be available as a remote actor by specifying **true** for registerAsRemoteService.

Here is an example:

.. code-block:: scala

  val supervisor = Supervisor(
    SupervisorConfig(
      AllForOnePermanentStrategy(List(classOf[Exception]), 3, 1000),
      Supervise(
        actorOf[MyActor1],
        Permanent,
        **true**)
      :: Nil))

Programmatic linking and supervision of Actors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Actors can at runtime create, spawn, link and supervise other actors. Linking and unlinking is done using one of the 'link' and 'unlink' methods available in the 'ActorRef' (therefore prefixed with 'self' in these examples).

Here is the API and how to use it from within an 'Actor':

.. code-block:: scala

  // link and unlink actors
  self.link(actorRef)
  self.unlink(actorRef)

  // link first, then start actor
  self.link(actorRef)

  // spawns (creates and starts) actors
  self.spawn[MyActor]
  self.spawnRemote[MyActor]

  // spawns and links Actors atomically
  self.spawnLink[MyActor]
  self.spawnLinkRemote[MyActor]

A child actor can tell the supervising actor to unlink him by sending him the 'Unlink(this)' message. When the supervisor receives the message he will unlink and shut down the child. The supervisor for an actor is available in the 'supervisor: Option[Actor]' method in the 'ActorRef' class. Here is how it can be used.

.. code-block:: scala

  if (supervisor.isDefined) supervisor.get ! Unlink(self)

  // Or shorter using 'foreach':

  supervisor.foreach(_ ! Unlink(self))

The supervising actor's side of things
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If a linked Actor is failing and throws an exception then an "Exit(deadActor, cause)' message will be sent to the supervisor (however you should never try to catch this message in your own message handler, it is managed by the runtime).

The supervising Actor also needs to define a fault handler that defines the restart strategy the Actor should accommodate when it traps an "Exit' message. This is done by setting the "faultHandler' field.

.. code-block:: scala

  protected var faultHandler: FaultHandlingStrategy

The different options are:

- AllForOnePermanentStrategy(trapExit, maxNrOfRetries, withinTimeRange)

  - trapExit is a List or Array of classes inheriting from Throwable, they signal which types of exceptions this actor will handle

- OneForOnePermanentStrategy(trapExit, maxNrOfRetries, withinTimeRange)

  - trapExit is a List or Array of classes inheriting from Throwable, they signal which types of exceptions this actor will handle

Here is an example:

.. code-block:: scala

  self.faultHandler = AllForOnePermanentStrategy(List(classOf[Throwable]), 3, 1000)

Putting all this together it can look something like this:

.. code-block:: scala

  class MySupervisor extends Actor {
    self.faultHandler = OneForOnePermanentStrategy(List(classOf[Throwable]), 5, 5000)

    def receive = {
      case Register(actor) =>
        self.link(actor)
    }
  }

You can also link an actor from outside the supervisor like this:

.. code-block:: scala

  val supervisor = Actor.registry.actorsFor(classOf[MySupervisor]).head
  supervisor.link(actor)

The supervised actor's side of things
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The supervised actor needs to define a life-cycle. This is done by setting the lifeCycle field as follows:

.. code-block:: scala

  self.lifeCycle = Permanent // Permanent or Temporary or UndefinedLifeCycle

In the supervised Actor you can override the "preRestart' and "postRestart' callback methods to add hooks into the restart process. These methods take the reason for the failure, e.g. the exception that caused termination and restart of the actor as argument. It is in these methods that **you** have to add code to do cleanup before termination and initialization after restart. Here is an example:

.. code-block:: scala

  class FaultTolerantService extends Actor {
    override def preRestart(reason: Throwable) {
      ... // clean up before restart
    }

    override def postRestart(reason: Throwable) {
      ... // reinit stable state after restart
    }
  }

Reply to initial senders
^^^^^^^^^^^^^^^^^^^^^^^^

Supervised actors have the option to reply to the initial sender within preRestart, postRestart and postStop. A reply within these methods is possible after receive has thrown an exception. When receive returns normally it is expected that any necessary reply has already been done within receive. Here's an example.

.. code-block:: scala

  class FaultTolerantService extends Actor {
    def receive = {
      case msg => {
        // do something that may throw an exception
        // ...

        self.reply("ok")
      }
    }

    override def preRestart(reason: scala.Throwable) {
      self.tryReply(reason.getMessage)
    }

    override def postStop() {
      self.tryReply("stopped by supervisor")
    }
  }

- A reply within preRestart or postRestart must be a safe reply via `self.tryReply` because an unsafe self.reply will throw an exception when the actor is restarted without having failed. This can be the case in context of AllForOne restart strategies.
- A reply within postStop must be a safe reply via `self.tryReply` because an unsafe self.reply will throw an exception when the actor has been stopped by the application (and not by a supervisor) after successful execution of receive (or no execution at all).

Handling too many actor restarts within a specific time limit
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If you remember, when you define the 'RestartStrategy' you also defined maximum number of restart retries within time in millis.

.. code-block:: scala

  AllForOnePermanentStrategy( //Restart policy, AllForOnePermanentStrategy or OneForOnePermanentStrategy
    List(classOf[Exception]), //What kinds of exception it will handle
    3,           // maximum number of restart retries
    5000         // within time in millis
  )

Now, what happens if this limit is reached?

What will happen is that the failing actor will send a system message to its supervisor called 'MaximumNumberOfRestartsWithinTimeRangeReached' with the following signature:

.. code-block:: scala

  case class MaximumNumberOfRestartsWithinTimeRangeReached(
    victim: ActorRef, maxNrOfRetries: Int, withinTimeRange: Int, lastExceptionCausingRestart: Throwable)

If you want to be able to take action upon this event (highly recommended) then you have to create a message handle for it in the supervisor.

Here is an example:

.. code-block:: scala

  val supervisor = actorOf(new Actor{
    self.faultHandler = OneForOnePermanentStrategy(List(classOf[Throwable]), 5, 5000)
    protected def receive = {
      case MaximumNumberOfRestartsWithinTimeRangeReached(
        victimActorRef, maxNrOfRetries, withinTimeRange, lastExceptionCausingRestart) =>
        ... // handle the error situation
    }
  })

You will also get this log warning similar to this:

.. code-block:: console

  WAR [20100715-14:05:25.821] actor: Maximum number of restarts [5] within time range [5000] reached.
  WAR [20100715-14:05:25.821] actor:     Will *not* restart actor [Actor[akka.actor.SupervisorHierarchySpec$CountDownActor:1279195525812]] anymore.
  WAR [20100715-14:05:25.821] actor:     Last exception causing restart was [akka.actor.SupervisorHierarchySpec$FireWorkerException: Fire the worker!].

If you don't define a message handler for this message then you don't get an error but the message is simply not sent to the supervisor. Instead you will get a log warning.

Supervising Typed Actors
------------------------

Declarative supervisor configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

To configure Typed Actors for supervision you have to consult the "TypedActorConfigurator' and its "configure' method. This method takes a "RestartStrategy' and an array of "Component' definitions defining the Typed Actors and their "LifeCycle'. Finally you call the "supervise' method to start everything up. The configuration elements reside in the "akka.config.JavaConfig' class and need to be imported statically.

Here is an example:

.. code-block:: scala

  import akka.config.Supervision._

  val manager = new TypedActorConfigurator

  manager.configure(
    AllForOnePermanentStrategy(List(classOf[Exception]), 3, 1000),
      List(
        SuperviseTypedActor(
          Foo.class,
          FooImpl.class,
          Permanent,
          1000),
        new SuperviseTypedActor(
          Bar.class,
          BarImpl.class,
          Permanent,
          1000)
    )).supervise

Then you can retrieve the Typed Actor as follows:

.. code-block:: java

  Foo foo = manager.getInstance(classOf[Foo])

Restart callbacks
^^^^^^^^^^^^^^^^^

Programatic linking and supervision of TypedActors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

TypedActors can be linked and unlinked just like actors - in fact the linking is done on the underlying actor:

.. code-block:: scala

  TypedActor.link(supervisor, supervised)

  TypedActor.unlink(supervisor, supervised)

If the parent TypedActor (supervisor) wants to be able to do handle failing child TypedActors, e.g. be able restart the linked TypedActor according to a given fault handling scheme then it has to set its 'trapExit' flag to an array of Exceptions that it wants to be able to trap:

.. code-block:: scala

  TypedActor.faultHandler(supervisor, AllForOnePermanentStrategy(Array(classOf[IOException]), 3, 2000))

For convenience there is an overloaded link that takes trapExit and faultHandler for the supervisor as arguments. Here is an example:

.. code-block:: scala

  import akka.actor.TypedActor._

  val foo = newInstance(classOf[Foo], 1000)
  val bar = newInstance(classOf[Bar], 1000)

  link(foo, bar, new AllForOnePermanentStrategy(Array(classOf[IOException]), 3, 2000))

  // alternative: chaining
  bar = faultHandler(foo, new AllForOnePermanentStrategy(Array(classOf[IOException]), 3, 2000))
    .newInstance(Bar.class, 1000)

  link(foo, bar
