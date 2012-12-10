.. _migration-2.1:

################################
 Migration Guide 2.0.x to 2.1.x
################################

Some parts of the 2.0 API have changed in the Akka 2.1 release. This guide lists the the changes and
explains what you will need to do to upgrade your program to work with Akka 2.1.

Migrating from Akka 2.0.x to Akka 2.1.x is relatively straightforward. In Akka 2.1 the API has
undergone some basic housekeeping, for example some package names have changed, but otherwise usage
is largely unchanged. User programs will generally only need simple, mechanical changes in order to
work with Akka 2.1.

If you are migrating from Akka 1.3.x you will need to follow the instructions for
`migrating from Akka 1.3.x to 2.0.x <http://doc.akka.io/docs/akka/2.0.3/project/migration-guide-1.3.x-2.0.x.html>`_
before following the instructions in this guide.

Scala Version
=============

Akka 2.1 uses a new version of Scala.
Change your project build and dependencies to Scala version ``@scalaVersion@``.

Config Dependency
=================

Akka's configuration system has graduated from Akka to become the `Typesafe config
<https://github.com/typesafehub/config>`_ project. The configuration system was previously embedded
within ``akka-actor.jar``, now it is specified as a dependency of ``akka-actor.jar``.

If your are using a build tool with automatic dependency resolution, such as sbt or Maven, then you 
will not notice a difference. Otherwise you will need to ensure that
`config-1.0.0.jar <http://mirrors.ibiblio.org/maven2/com/typesafe/config/1.0.0/>`_
is present on your classpath.

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
``akka.util.Duration``               ``scala.concurrent.duration.Duration``
``akka.util.duration``               ``scala.concurrent.duration``
``akka.util.Deadline``               ``scala.concurrent.duration.Deadline``
``akka.util.NonFatal``               ``scala.util.control.NonFatal``
``akka.japi.Util.manifest``          ``akka.japi.Util.classTag``
==================================== ====================================

Scheduler Dispatcher
====================

The ``ExecutionContext`` to use for running scheduled tasks must now be specified.
You can use an Akka ``Dispatcher`` for this purpose.

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
  getContext().system().scheduler().scheduleOnce(Duration.create(
    10, TimeUnit.SECONDS), getSelf(), new Reconnect(), 
    getContext().getDispatcher());

  // Use ActorSystem's default Dispatcher as ExecutionContext
  system.scheduler().scheduleOnce(Duration.create(50, TimeUnit.MILLISECONDS),
    new Runnable() {
      @Override
      public void run() {
        testActor.tell(System.currentTimeMillis());
      }
    }, system.dispatcher());


API Changes to Future - Scala
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
    // When filter fails, it will have a java.util.NoSuchElementException
    case m: NoSuchElementException => 
  }



API Changes to Future - Java
============================

v2.0::

      ExecutorService yourExecutorServiceGoesHere = Executors.newSingleThreadExecutor();
      ExecutionContextExecutorService ec =
        ExecutionContexts.fromExecutorService(yourExecutorServiceGoesHere);

      // Use ec with your Futures
      Future<String> f1 = Futures.successful("foo", ec);

      // Then you shut the ec down somewhere at the end of your application.
      ec.shutdown();

v2.1::

      ExecutorService yourExecutorServiceGoesHere = Executors.newSingleThreadExecutor();
      ExecutionContext ec =
        ExecutionContexts.fromExecutorService(yourExecutorServiceGoesHere);

      //No need to pass the ExecutionContext here
      Future<String> f1 = Futures.successful("foo");

      // Then you shut the ExecutorService down somewhere at the end of your application.
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
    Future<String> future1 = Futures.successful("value").andThen(
      new OnComplete<String>() {
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

API changes to DynamicAccess
============================

All methods with scala.Either[Throwable, X] have been changed to use scala.util.Try[X].

DynamicAccess.withErrorHandling has been removed since scala.util.Try now fulfills that role.

API changes to Serialization
============================

All methods with scala.Either[Throwable, X] have been changed to use scala.util.Try[X].

Empty Props
===========

v2.0 Scala::

  val router2 = system.actorOf(Props().withRouter(
    RoundRobinRouter(routees = routees)))

v2.1 Scala::

  val router2 = system.actorOf(Props.empty.withRouter(
    RoundRobinRouter(routees = routees)))

v2.0 Java::

  ActorRef router2 = system.actorOf(new Props().withRouter(
    RoundRobinRouter.create(routees)));

v2.1 Java::

  ActorRef router2 = system.actorOf(Props.empty().withRouter(
    RoundRobinRouter.create(routees)));

Props: Function-based creation
==============================

v2.0 Scala::

  Props(context => { case someMessage => context.sender ! someMessage })

v2.1 Scala::

  Props(new Actor { def receive = { case someMessage => sender ! someMessage } })

Failing Send
============

When failing to send to a remote actor or an actor with a bounded or durable mailbox the message will 
now be silently delivered to ``ActorSystem.deadletters`` instead of throwing an exception.

Graceful Stop Exception
=======================

If the target actor of ``akka.pattern.gracefulStop`` isn't terminated within the 
timeout then the ``Future`` is completed with a failure of ``akka.pattern.AskTimeoutException``.
In 2.0 it was ``akka.actor.ActorTimeoutException``.

getInstance for Singletons - Java
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

The default value of akka.remote.log-remote-lifecycle-events has changed to **on**.
If you don't want these events in the log then you need to add this to your configuration::

  akka.remote.log-remote-lifecycle-events = off

Stash postStop
==============

Both Actors and UntypedActors using ``Stash`` now override postStop to make sure that
stashed messages are put into the dead letters when the actor stops. Make sure you call
super.postStop if you override it.

Forwarding Terminated messages
==============================

Forwarding ``Terminated`` messages is no longer supported. Instead, if you forward
``Terminated`` you should send the information in your own message.

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


Custom Routers and Resizers
===========================

The API of ``RouterConfig``, ``RouteeProvider`` and ``Resizer`` has been 
cleaned up. If you use these to build your own router functionality the 
compiler will tell you if you need to make adjustments. 

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
      val (keep, abandon) = currentRoutees.splitAt(currentRoutees.length +
        requestedCapacity)
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

Duration and Timeout
====================

The :class:`akka.util.Duration` class has been moved into the Scala library under
the ``scala.concurrent.duration`` package. Several changes have been made to tighten
up the duration and timeout API.

:class:`FiniteDuration` is now used more consistently throught the API.
The advantage is that if you try to pass a possibly non-finite duration where
it does not belong you’ll get compile errors instead of runtime exceptions.

The main source incompatibility is that you may have to change the declared
type of fields from ``Duration`` to ``FiniteDuration`` (factory methods already
return the more precise type wherever possible).

Another change is that ``Duration.parse`` was not accepted by the Scala library
maintainers; use ``Duration.create`` instead.

v2.0::

  final Duration d = Duration.parse("1 second");
  final Timeout t = new Timeout(d);

v2.1::

  final FiniteDuration d = Duration.create(1, TimeUnit.SECONDS);
  final Timeout t = new Timeout(d); // always required finite duration, now enforced

Package Name Changes in Remoting
================================

The package name of all classes in the ``akka-remote.jar`` artifact now starts with ``akka.remote``.
This has been done to enable OSGi bundles that don't have conflicting package names.

Change the following import statements. Please note that serializers are often referenced from
configuration files.

Search -> Replace with::

  akka.routing.RemoteRouterConfig -> 
  akka.remote.routing.RemoteRouterConfig

  akka.serialization.ProtobufSerializer ->
  akka.remote.serialization.ProtobufSerializer

  akka.serialization.DaemonMsgCreateSerializer -> 
  akka.remote.serialization.DaemonMsgCreateSerializer


Package Name Changes in Durable Mailboxes
=========================================

The package names of all classes in the ``akka-file-mailbox.jar`` artifact now start with ``akka.actor.mailbox.filebased``.
This has been done to enable OSGi bundles that don't have conflicting package names.

Change the following import statements. Please note that the ``FileBasedMailboxType`` is often referenced from configuration.

Search -> Replace with::

  akka.actor.mailbox.FileBasedMailboxType ->
  akka.actor.mailbox.filebased.FileBasedMailboxType

  akka.actor.mailbox.FileBasedMailboxSettings ->
  akka.actor.mailbox.filebased.FileBasedMailboxSettings

  akka.actor.mailbox.FileBasedMessageQueue ->
  akka.actor.mailbox.filebased.FileBasedMessageQueue

  akka.actor.mailbox.filequeue.* ->
  akka.actor.mailbox.filebased.filequeue.*

   
Actor Receive Timeout
=====================

The API for setting and querying the receive timeout has been made more
consistent in always taking and returning a ``Duration``; the wrapping in
``Option`` has been removed.

(Samples for Java, Scala sources are affected in exactly the same way.)

v2.0::

  getContext().setReceiveTimeout(Duration.create(10, SECONDS));
  final Option<Duration> timeout = getContext().receiveTimeout();
  final isSet = timeout.isDefined();
  resetReceiveTimeout();

v2.1::

  getContext().setReceiveTimeout(Duration.create(10, SECONDS));
  final Duration timeout = getContext().receiveTimeout();
  final isSet = timeout.isFinite();
  getContext().setReceiveTimeout(Duration.Undefined());

ConsistentHash
==============

``akka.routing.ConsistentHash`` has been changed into an immutable data structure.

v2.0::

  val consistentHash = new ConsistentHash(Seq(a1, a2, a3), replicas = 10)
  consistentHash += a4
  val a = consistentHash.nodeFor(data)

v2.1::

  var consistentHash = ConsistentHash(Seq(a1, a2, a3), replicas = 10)
  consistentHash = consistentHash :+ a4
  val a = consistentHash.nodeFor(data)

