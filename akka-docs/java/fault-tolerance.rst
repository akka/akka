.. _fault-tolerance-java:

Fault Tolerance Through Supervisor Hierarchies (Java)
=====================================================

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **SOLID**

The "let it crash" approach to fault/error handling, implemented by linking actors, is very different to what Java and most non-concurrency oriented languages/frameworks have adopted. It’s a way of dealing with failure that is designed for concurrent and distributed systems.

Concurrency
-----------

Throwing an exception in concurrent code (let’s assume we are using non-linked actors), will just simply blow up the thread that currently executes the actor.

- There is no way to find out that things went wrong (apart from inspecting the stack trace).
- There is nothing you can do about it.

Here actors provide a clean way of getting notification of the error and do something about it.

Linking actors also allow you to create sets of actors where you can be sure that either:
- All are dead
- None are dead

This is very useful when you have thousands of concurrent actors. Some actors might have implicit dependencies and together implement a service, computation, user session etc.

It encourages non-defensive programming. Don’t try to prevent things from go wrong, because they will, whether you want it or not. Instead; expect failure as a natural state in the life-cycle of your app, crash early and let someone else (that sees the whole picture), deal with it.

Distributed actors
------------------

You can’t build a fault-tolerant system with just one single box - you need at least two. Also, you (usually) need to know if one box is down and/or the service you are talking to on the other box is down. Here actor supervision/linking is a critical tool for not only monitoring the health of remote services, but to actually manage the service, do something about the problem if the actor or node is down. Such as restarting actors on the same node or on another node.

In short, it is a very different way of thinking, but a way that is very useful (if not critical) to building fault-tolerant highly concurrent and distributed applications, which is as valid if you are writing applications for the JVM or the Erlang VM (the origin of the idea of "let-it-crash" and actor supervision).

Supervision
-----------

Supervisor hierarchies originate from `Erlang’s OTP framework <http://www.erlang.org/doc/design_principles/sup_princ.html#5>`_.

A supervisor is responsible for starting, stopping and monitoring its child processes. The basic idea of a supervisor is that it should keep its child processes alive by restarting them when necessary. This makes for a completely different view on how to write fault-tolerant servers. Instead of trying all things possible to prevent an error from happening, this approach embraces failure. It shifts the view to look at errors as something natural and something that **will** happen, instead of trying to prevent it; embraces it. Just ‘Let It Crash™’, since the components will be reset to a stable state and restarted upon failure.

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

There are two different callbacks that an UntypedActor or TypedActor can hook in to:

- Pre restart
- Post restart

These are called prior to and after the restart upon failure and can be used to clean up and reset/reinitialize state upon restart. This is important in order to reset the component failure and leave the component in a fresh and stable state before consuming further messages.

Defining a supervisor's restart strategy
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Both the Typed Actor supervisor configuration and the Actor supervisor configuration take a ‘FaultHandlingStrategy’ instance which defines the fault management. The different strategies are:

- AllForOne
- OneForOne

These have the semantics outlined in the section above.

Here is an example of how to define a restart strategy:

.. code-block:: java

  new AllForOneStrategy( //Or OneForOneStrategy
    new Class[]{ Exception.class }, //List of Exceptions/Throwables to handle
    3,               // maximum number of restart retries
    5000             // within time in millis
  )

Defining actor life-cycle
^^^^^^^^^^^^^^^^^^^^^^^^^

The other common configuration element is the ‘LifeCycle’ which defines the life-cycle. The supervised actor can define one of two different life-cycle configurations:

- Permanent: which means that the actor will always be restarted.
- Temporary: which means that the actor will **not** be restarted, but it will be shut down through the regular shutdown process so the 'postStop' callback function will called.

Here is an example of how to define the life-cycle:

.. code-block:: java

  import static akka.config.Supervision.*;

  getContext().setLifeCycle(permanent()); //permanent() means that the component will always be restarted
  getContext().setLifeCycle(temporary()); //temporary() means that the component will not be restarted, but rather shut down normally

Supervising Untyped Actor
-------------------------

Declarative supervisor configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Actor’s supervision can be declaratively defined by creating a ‘Supervisor’ factory object. Here is an example:

.. code-block:: java

  import static akka.config.Supervision.*;
  import static akka.actor.Actors.*;

  Supervisor supervisor = Supervisor.apply(
      new SupervisorConfig(
        new AllForOneStrategy(new Class[]{Exception.class}, 3, 5000),
        new Supervise[] {
          new Supervise(
            actorOf(MyActor1.class),
            permanent()),
          new Supervise(
            actorOf(MyActor2.class),
            permanent())
         }));

Supervisors created like this are implicitly instantiated and started.

To configure a handler function for when the actor underlying the supervisor receives a MaximumNumberOfRestartsWithinTimeRangeReached message, you can specify
a Procedure2<ActorRef,MaximumNumberOfRestartsWithinTimeRangeReached> when creating the SupervisorConfig. This handler will be called with the ActorRef of the supervisor and the
MaximumNumberOfRestartsWithinTimeRangeReached message.

.. code-block:: java

  import static akka.config.Supervision.*;
  import static akka.actor.Actors.*;
  import akka.event.EventHandler;
  import akka.japi.Procedure2;


  Procedure2<ActorRef, MaximumNumberOfRestartsWithinTimeRangeReached> handler = new Procedure2<ActorRef, MaximumNumberOfRestartsWithinTimeRangeReached>() {
    public void apply(ActorRef ref, MaximumNumberOfRestartsWithinTimeRangeReached max) {
      EventHandler.error(ref, max);
    }
  };

  Supervisor supervisor = Supervisor.apply(
      new SupervisorConfig(
        new AllForOneStrategy(new Class[]{Exception.class}, 3, 5000),
        new Supervise[] {
          new Supervise(
            actorOf(MyActor1.class),
            permanent()),
          new Supervise(
            actorOf(MyActor2.class),
            permanent())
         }, handler));

You can link and unlink actors from a declaratively defined supervisor using the 'link' and 'unlink' methods:

.. code-block:: java

  Supervisor supervisor = Supervisor.apply(...);
  supervisor.link(..);
  supervisor.unlink(..);

You can also create declarative supervisors through the 'SupervisorFactory' factory object. Use this factory instead of the 'Supervisor' factory object if you want to control instantiation and starting of the Supervisor, if not then it is easier and better to use the 'Supervisor' factory object.

Example usage:

.. code-block:: java

  import static akka.config.Supervision.*;
  import static akka.actor.Actors.*;

  SupervisorFactory factory = new SupervisorFactory(
    new SupervisorConfig(
      new OneForOneStrategy(new Class[]{Exception.class}, 3, 5000),
      new Supervise[] {
        new Supervise(
          actorOf(MyActor1.class),
          permanent()),
        new Supervise(
          actorOf(MyActor2.class),
          temporary())
     }));

Then create a new instance our Supervisor and start it up explicitly.

.. code-block:: java

  SupervisorFactory supervisor = factory.newInstance();
  supervisor.start(); // start up all managed servers

Declaratively define actors as remote services
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can expose your actors as remote services by specifying the registerAsRemote to **true** in Supervise.

Here is an example:

.. code-block:: java

  import static akka.config.Supervision.*;
  import static akka.actor.Actors.*;

  Supervisor supervisor = Supervisor.apply(
    new SupervisorConfig(
      new AllForOneStrategy(new Class[]{Exception.class}, 3, 5000),
      new Supervise[] {
        new Supervise(
          actorOf(MyActor1.class),
          permanent(),
          true)
       }));

Programmatic linking and supervision of Untyped Actors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Untyped Actors can at runtime create, spawn, link and supervise other actors. Linking and unlinking is done using one of the 'link' and 'unlink' methods available in the 'ActorRef' (therefore prefixed with getContext() in these examples).

Here is the API and how to use it from within an 'Actor':

.. code-block:: java

  // link and unlink actors
  getContext().link(actorRef);
  getContext().unlink(actorRef);

  // starts and links Actors atomically
  getContext().link(actorRef);

  // spawns (creates and starts) actors
  getContext().spawn(MyActor.class);

  // spawns and links Actors atomically
  getContext().spawnLink(MyActor.class);

A child actor can tell the supervising actor to unlink him by sending him the 'Unlink(this)' message. When the supervisor receives the message he will unlink and shut down the child. The supervisor for an actor is available in the 'supervisor: Option[Actor]' method in the 'ActorRef' class. Here is how it can be used.

.. code-block:: java

  ActorRef supervisor = getContext().getSupervisor();
  if (supervisor != null) supervisor.tell(new Unlink(getContext()))

The supervising actor's side of things
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If a linked Actor is failing and throws an exception then an ‘new Exit(deadActor, cause)’ message will be sent to the supervisor (however you should never try to catch this message in your own message handler, it is managed by the runtime).

The supervising Actor also needs to define a fault handler that defines the restart strategy the Actor should accommodate when it traps an ‘Exit’ message. This is done by setting the ‘setFaultHandler’ method.

The different options are:

- AllForOneStrategy(trapExit, maxNrOfRetries, withinTimeRange)

  - trapExit is an Array of classes inheriting from Throwable, they signal which types of exceptions this actor will handle

- OneForOneStrategy(trapExit, maxNrOfRetries, withinTimeRange)

  - trapExit is an Array of classes inheriting from Throwable, they signal which types of exceptions this actor will handle

Here is an example:

.. code-block:: java

  getContext().setFaultHandler(new AllForOneStrategy(new Class[]{MyException.class, IOException.class}, 3, 1000));

Putting all this together it can look something like this:

.. code-block:: java

  class MySupervisor extends UntypedActor {
    public MySupervisor() {
      getContext().setFaultHandler(new AllForOneStrategy(new Class[]{MyException.class, IOException.class}, 3, 1000));
    }

    public void onReceive(Object message) throws Exception {
      if (message instanceof Register) {
        Register event = (Register)message;
        UntypedActorRef actor = event.getActor();
        context.link(actor);
      } else throw new IllegalArgumentException("Unknown message: " + message);
    }
  }

You can also link an actor from outside the supervisor like this:

.. code-block:: java

  UntypedActor supervisor = Actors.registry().actorsFor(MySupervisor.class])[0];
  supervisor.link(actorRef);

The supervised actor's side of things
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The supervised actor needs to define a life-cycle. This is done by setting the lifeCycle field as follows:

.. code-block:: java

  import static akka.config.Supervision.*;

  getContext().setLifeCycle(permanent()); // Permanent or Temporary

Default is 'Permanent' so if you don't set the life-cycle then that is what you get.

In the supervised Actor you can override the ‘preRestart’ and ‘postRestart’ callback methods to add hooks into the restart process. These methods take the reason for the failure, e.g. the exception that caused termination and restart of the actor as argument. It is in these methods that **you** have to add code to do cleanup before termination and initialization after restart. Here is an example:

.. code-block:: java

  class FaultTolerantService extends UntypedActor {

    @Override
    public void preRestart(Throwable reason) {
      ... // clean up before restart
    }

    @Override
    public void postRestart(Throwable reason) {
      ... // reinit stable state after restart
    }
  }

Reply to initial senders
^^^^^^^^^^^^^^^^^^^^^^^^

Supervised actors have the option to reply to the initial sender within preRestart, postRestart and postStop. A reply within these methods is possible after receive has thrown an exception. When receive returns normally it is expected that any necessary reply has already been done within receive. Here's an example.

.. code-block:: java

  public class FaultTolerantService extends UntypedActor {
      public void onReceive(Object msg) {
          // do something that may throw an exception
          // ...

          getContext().tryReply("ok");
      }

      @Override
      public void preRestart(Throwable reason) {
          getContext().tryReply(reason.getMessage());
      }

      @Override
      public void postStop() {
          getContext().tryReply("stopped by supervisor");
      }
  }

- A reply within preRestart or postRestart must be a safe reply via getContext().tryReply() because a getContext().reply() will throw an exception when the actor is restarted without having failed. This can be the case in context of AllForOne restart strategies.
- A reply within postStop must be a safe reply via getContext().tryReply() because a getContext().reply() will throw an exception when the actor has been stopped by the application (and not by a supervisor) after successful execution of receive (or no execution at all).

Handling too many actor restarts within a specific time limit
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If you remember, when you define the 'RestartStrategy' you also defined maximum number of restart retries within time in millis.

.. code-block:: java

  new AllForOneStrategy( // FaultHandlingStrategy policy (AllForOneStrategy or OneForOneStrategy)
    new Class[]{MyException.class, IOException.class}, //What types of errors will be handled
    3,               // maximum number of restart retries
    5000             // within time in millis
  );

Now, what happens if this limit is reached?

What will happen is that the failing actor will send a system message to its supervisor called 'MaximumNumberOfRestartsWithinTimeRangeReached' with the following these properties:

- victim: ActorRef
- maxNrOfRetries: int
- withinTimeRange: int
- lastExceptionCausingRestart: Throwable

If you want to be able to take action upon this event (highly recommended) then you have to create a message handle for it in the supervisor.

Here is an example:

.. code-block:: java

  public class SampleUntypedActorSupervisor extends UntypedActor {
    ...

    public void onReceive(Object message) throws Exception {
      if (message instanceof MaximumNumberOfRestartsWithinTimeRangeReached) {
         MaximumNumberOfRestartsWithinTimeRangeReached event = (MaximumNumberOfRestartsWithinTimeRangeReached)message;
         ... = event.getVictim();
         ... = event.getMaxNrOfRetries();
         ... = event.getWithinTimeRange();
         ... = event.getLastExceptionCausingRestart();
      } else throw new IllegalArgumentException("Unknown message: " + message);
    }
  }

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

To configure Typed Actors for supervision you have to consult the ‘TypedActorConfigurator’ and its ‘configure’ method. This method takes a ‘RestartStrategy’ and an array of ‘Component’ definitions defining the Typed Actors and their ‘LifeCycle’. Finally you call the ‘supervise’ method to start everything up. The Java configuration elements reside in the ‘akka.config.JavaConfig’ class and need to be imported statically.

Here is an example:

.. code-block:: java

  import static akka.config.Supervision.*;
  import static akka.config.SupervisorConfig.*;

  TypedActorConfigurator manager = new TypedActorConfigurator();

  manager.configure(
    new AllForOneStrategy(new Class[]{Exception.class}, 3, 1000),
      new SuperviseTypedActor[] {
        new SuperviseTypedActor(
          Foo.class,
          FooImpl.class,
          temporary(),
          1000),
        new SuperviseTypedActor(
          Bar.class,
          BarImpl.class,
          permanent(),
          1000)
    }).supervise();

Then you can retrieve the Typed Actor as follows:

.. code-block:: java

  Foo foo = (Foo) manager.getInstance(Foo.class);

Restart callbacks
^^^^^^^^^^^^^^^^^

In the supervised TypedActor you can override the ‘preRestart’ and ‘postRestart’ callback methods to add hooks into the restart process. These methods take the reason for the failure, e.g. the exception that caused termination and restart of the actor as argument. It is in these methods that **you** have to add code to do cleanup before termination and initialization after restart. Here is an example:

.. code-block:: java

  class FaultTolerantService extends TypedActor {

    @Override
    public void preRestart(Throwable reason) {
      ... // clean up before restart
    }

    @Override
    public void postRestart(Throwable reason) {
      ... // reinit stable state after restart
    }
  }

Programatic linking and supervision of TypedActors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

TypedActors can be linked and unlinked just like UntypedActors:

.. code-block:: java

  TypedActor.link(supervisor, supervised);

  TypedActor.unlink(supervisor, supervised);

If the parent TypedActor (supervisor) wants to be able to do handle failing child TypedActors, e.g. be able restart the linked TypedActor according to a given fault handling scheme then it has to set its ‘trapExit’ flag to an array of Exceptions that it wants to be able to trap:

.. code-block:: java

  TypedActor.faultHandler(supervisor, new AllForOneStrategy(new Class[]{IOException.class}, 3, 2000));

For convenience there is an overloaded link that takes trapExit and faultHandler for the supervisor as arguments. Here is an example:

.. code-block:: java

  import static akka.actor.TypedActor.*;
  import static akka.config.Supervision.*;

  foo = newInstance(Foo.class, FooImpl.class, 1000);
  bar = newInstance(Bar.class, BarImpl.class, 1000);

  link(foo, bar, new AllForOneStrategy(new Class[]{IOException.class}, 3, 2000));

  // alternative: chaining
  bar = faultHandler(foo, new AllForOneStrategy(new Class[]{IOException.class}, 3, 2000)).newInstance(Bar.class, 1000);

  link(foo, bar);
