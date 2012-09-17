.. _migration-2.1:

################################
 Migration Guide 2.0.x to 2.1.x
################################

The 2.1 release contains several structural changes that require some
simple, mechanical source-level changes in client code. Several things have
been moved to Scala standard library, such as ``Future``, and some package
names have been changed in Remoting.

When migrating from 1.3.x to 2.1.x you should first follow the instructions for
migrating `1.3.x to 2.0.x <http://doc.akka.io/docs/akka/2.0.3/project/migration-guide-1.3.x-2.0.x.html>`_.

Scala Version
=============

Change your project build and dependencies to Scala version:

.. parsed-literal::

  |scalaVersion|

Config Dependency
=================

`Typesafe config <https://github.com/typesafehub/config>`_ library is a normal 
dependency of akka-actor and it is no longer embedded in ``akka-actor.jar``. 
If your are using a build tool with dependency resolution, such as sbt or maven you 
will not notice the difference, but if you have manually constructed classpaths 
you need to add `config-0.5.0.jar <http://mirrors.ibiblio.org/maven2/com/typesafe/config/0.5.0/>`_.

Pieces Moved to Scala Standard Library
======================================

Change the following import statements.

==================================== ====================================
Search                               Replace with
==================================== ====================================
``akka.dispatch.Await``              ``scala.concurrent.Await``
``akka.dispatch.Future``             ``scala.concurrent.Future``
``akka.dispatch.Promise``            ``scala.concurrent.Promise``
``akka.dispatch.ExecutionContext``   ``scala.concurrent.ExecutionContext``
``akka.util.Duration``               ``scala.concurrent.util.Duration``
``akka.util.duration``               ``scala.concurrent.util.duration``
``akka.util.Deadline``               ``scala.concurrent.util.Deadline``
``akka.util.NonFatal``               ``scala.util.control.NonFatal``
``akka.japi.Util.manifest``          ``akka.japi.Util.classTag``
==================================== ====================================

Scheduler Dispatcher
====================

The ``ExecutionContext`` to use for running scheduled tasks must be specified.

Scala:

::
  
  import context.dispatcher // Use this Actors' Dispatcher as ExecutionContext
  context.system.scheduler.scheduleOnce(10 seconds, self, Reconnect)

  import system.dispatcher // Use ActorSystem's default Dispatcher as ExecutionContext
  system.scheduler.scheduleOnce(50 milliseconds) {
    testActor ! System.currentTimeMillis
  }

Java:
::
  
  // Use this Actors' Dispatcher as ExecutionContext
  getContext().system().scheduler().scheduleOnce(Duration.parse("10 seconds", getSelf(), 
    new Reconnect(), getContext().getDispatcher());

  // Use ActorSystem's default Dispatcher as ExecutionContext
  system.scheduler().scheduleOnce(Duration.create(50, TimeUnit.MILLISECONDS), new Runnable() {
      @Override
      public void run() {
        testActor.tell(System.currentTimeMillis());
      }
    }, system.dispatcher());


API Changes of Future - Scala
=============================

v2.0::

  def square(i: Int): Future[Int] = Promise successful i * i

v2.1::

  def square(i: Int): Future[Int] = Future successful i * i

v2.0::

  val failedFilter = future1.filter(_ % 2 == 1).recover {
    case m: MatchError => //When filter fails, it will have a MatchError
  }

v2.1::

  val failedFilter = future1.filter(_ % 2 == 1).recover {
    case m: NoSuchElementException => //When filter fails, it will have a java.util.NoSuchElementException
  }



API Changes of Future - Java
============================

v2.0::

      ExecutorService yourExecutorServiceGoesHere = Executors.newSingleThreadExecutor();
      ExecutionContextExecutorService ec =
        ExecutionContexts.fromExecutorService(yourExecutorServiceGoesHere);

      //Use ec with your Futures
      Future<String> f1 = Futures.successful("foo", ec);

      // Then you shut the ec down somewhere at the end of your program/application.
      ec.shutdown();

v2.1::

      ExecutorService yourExecutorServiceGoesHere = Executors.newSingleThreadExecutor();
      ExecutionContext ec =
        ExecutionContexts.fromExecutorService(yourExecutorServiceGoesHere);

      //No need to pass the ExecutionContext here
      Future<String> f1 = Futures.successful("foo");

      // Then you shut the ExecutorService down somewhere at the end of your program/application.
      yourExecutorServiceGoesHere.shutdown();

v2.0::

    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, system.dispatcher());

v2.1::

    final ExecutionContext ec = system.dispatcher();

    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, ec);

v2.0::

    Future<String> future1 = Futures.successful("value", system.dispatcher()).andThen(
      new OnComplete<String>() {
        public void onComplete(Throwable failure, String result) {
          if (failure != null)
              sendToIssueTracker(failure);
        }
    }).andThen(new OnComplete<String>() {
      public void onComplete(Throwable failure, String result) {
        if (result != null)
          sendToTheInternetz(result);
      }
    });              

v2.1::

    final ExecutionContext ec = system.dispatcher();
    Future<String> future1 = Futures.successful("value").andThen(new OnComplete<String>() {
        public void onComplete(Throwable failure, String result) {
            if (failure != null)
                sendToIssueTracker(failure);
        }
    }, ec).andThen(new OnComplete<String>() {
      public void onComplete(Throwable failure, String result) {
        if (result != null)
          sendToTheInternetz(result);
      }
    }, ec);

API changes of DynamicAccess
============================

All methods with scala.Either[Throwable, X] have been changed to used scala.util.Try[X].

DynamicAccess.withErrorHandling has been removed since scala.util.Try now fulfills that role.

API changes of Serialization
============================

All methods with scala.Either[Throwable, X] have been changed to used scala.util.Try[X].

Empty Props
===========

v2.0 Scala::

  val router2 = system.actorOf(Props().withRouter(
    RoundRobinRouter(routees = routees)))

v2.1 Scala::

  val router2 = system.actorOf(Props[ExampleActor1].withRouter(
    RoundRobinRouter(routees = routees)))    

v2.0 Java::

  ActorRef router2 = system.actorOf(new Props(ExampleActor.class).withRouter(
    RoundRobinRouter.create(routees)));

v2.1 Java::

  ActorRef router2 = system.actorOf(new Props().withRouter(RoundRobinRouter.create(routees)));

Props: Function-based creation
==============================

v2.0 Scala::

  Props(context => { case someMessage => context.sender ! someMessage })

v2.1 Scala::

  Props(new Actor { def receive = { case someMessage => sender ! someMessage } })

Failing Send
============

When failing to send to a remote actor or actor with bounded or durable mailbox the message will 
silently be delivered to ``ActorSystem.deadletters`` instead of throwing an exception.

Graceful Stop Exception
=======================

If the target actor of ``akka.pattern.gracefulStop`` isn't terminated within the 
timeout the ``Future`` is completed with failure ``akka.pattern.AskTimeoutException``.
In 2.0 it was ``akka.actor.ActorTimeoutException``.

getInstance for singeltons - Java
====================================

v2.0::

  import static akka.actor.Actors.*;

  if (msg.equals("done")) {
    myActor.tell(poisonPill());
  } else if (msg == Actors.receiveTimeout()) {

v2.1::

  import akka.actor.PoisonPill;      
  import akka.actor.ReceiveTimeout;

  if (msg.equals("done")) {
    myActor.tell(PoisonPill.getInstance());
  } else if (msg == ReceiveTimeout.getInstance()) {


Testkit Probe Reply
===================

v2.0::

  probe.sender ! "world"

v2.1::

  probe.reply("world")  

log-remote-lifecycle-events
===========================

Default value of akka.remote.log-remote-lifecycle-events has changed to **on**.
If you don't want these in the log you need to add this to your configuration::

  akka.remote.log-remote-lifecycle-events = off

Stash postStop
==============

Both Actors and UntypedActors using ``Stash`` now overrides postStop to make sure that
stashed messages are put into the dead letters when the actor stops, make sure you call
super.postStop if you override it.


Forward of Terminated message
=============================

Forward of ``Terminated`` message is no longer supported. Instead, if you forward
``Terminated`` you should send the information in you own message.

v2.0::

  context.watch(subject)

  def receive = {
    case t @ Terminated => someone forward t
  }

v2.1::

  case class MyTerminated(subject: ActorRef)

  context.watch(subject)

  def receive = {
    case Terminated(s) => someone forward MyTerminated(s)
  }


Custom Router or Resizer
========================

The API of ``RouterConfig``, ``RouteeProvider`` and ``Resizer`` has been 
cleaned up. If you use these to build your own router functionality the 
compiler will tell you you to do some adjustments. 

v2.0::

  class MyRouter(target: ActorRef) extends RouterConfig {
    override def createRoute(p: Props, prov: RouteeProvider): Route = {
      prov.createAndRegisterRoutees(p, 1, Nil)

v2.1::

  class MyRouter(target: ActorRef) extends RouterConfig {
    override def createRoute(provider: RouteeProvider): Route = {
      provider.createRoutees(1)

v2.0::

  def resize(props: Props, routeeProvider: RouteeProvider): Unit = {
    val currentRoutees = routeeProvider.routees
    val requestedCapacity = capacity(currentRoutees)

    if (requestedCapacity > 0) {
      val newRoutees = routeeProvider.createRoutees(props, requestedCapacity, Nil)
      routeeProvider.registerRoutees(newRoutees)
    } else if (requestedCapacity < 0) {
      val (keep, abandon) = currentRoutees.splitAt(currentRoutees.length + requestedCapacity)
      routeeProvider.unregisterRoutees(abandon)
      delayedStop(routeeProvider.context.system.scheduler, abandon)(
        routeeProvider.context.dispatcher)
    }


v2.1::

  def resize(routeeProvider: RouteeProvider): Unit = {
    val currentRoutees = routeeProvider.routees
    val requestedCapacity = capacity(currentRoutees)

    if (requestedCapacity > 0) routeeProvider.createRoutees(requestedCapacity)
    else if (requestedCapacity < 0) routeeProvider.removeRoutees(
      -requestedCapacity, stopDelay)

Package Name Changes in Remoting
================================

The package name of all classes in the ``akka-remote.jar`` artifact now starts with akka.remote.
This has been done to enable OSGi bundles that don't have conflicting package names.

Change the following import statements. Please note that the serializers are often referenced from configuration.

================================================ =======================================================
Search                                           Replace with
================================================ =======================================================
``akka.routing.RemoteRouterConfig``              ``akka.remote.routing.RemoteRouterConfig``
``akka.serialization.ProtobufSerializer``        ``akka.remote.serialization.ProtobufSerializer``
``akka.serialization.DaemonMsgCreateSerializer`` ``akka.remote.serialization.DaemonMsgCreateSerializer``
================================================ =======================================================
