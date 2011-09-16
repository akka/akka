Release Notes
==============

Release 1.2
-----------

This release, while containing several substantial improvements, focuses on
paving the way for the upcoming 2.0 release. A selection of changes is
presented in the following, for the full list of tickets closed during the
development cycle please refer to
`the issue tracker <https://www.assembla.com/spaces/akka/milestones/356697-1-2>`_.

- **Actor:** 

  - unified :class:`Channel` abstraction for :class:`Promise` & :class:`Actor`

  - reintegrate invocation tracing (to be enabled per class and globally)

  - make last message available during :meth:`preRestart()`

  - experimental :meth:`freshInstance()` life-cycle hook for priming the new instance during restart

  - new textual primitives :meth:`tell` (``!``) and :meth:`ask` (``?``, formerly ``!!!``)

  - timeout for :meth:`ask` Futures taken from implicit argument (currently with fallback to deprecated ``ActorRef.timeout``

- **durable mailboxes:**

  - beanstalk, file, mongo, redis

- **Future:**

  - :meth:`onTimeout` callback

  - select dispatcher for execution by implicit argument

  - add safer cast methods :meth:`as[T]: T` and :meth:`mapTo[T]: Future[T]`

- **TestKit:**

  - add :class:`TestProbe` (can receive, reply and forward messages, supports all :class:`TestKit` assertions)

  - add :meth:`TestKit.awaitCond`

  - support global time-factor for all timing assertions (for running on busy CI servers)

- **FSM:**

  - add :class:`TestFSMRef`

  - add :class:`LoggingFSM` (transition tracing, rolling event log)

- **updated dependencies:**

  - **Scala 2.9.1**

  - Jackson 1.8.0

  - Netty 3.2.5

  - Protobuf 2.4.1

  - ScalaTest 1.6.1

- various fixes, small improvements and documentation updates

- several **deprecations** in preparation for 2.0

  ================================  =====================
  Method                            Replacement
  ================================  =====================
  Actor.preRestart(cause)           Actor.preRestart(cause, lastMsg)
  ActorRef.sendOneWay               ActorRef.tell
  ActorRef.sendOneWaySafe           ActorRef.tryTell
  ActorRef.sendRequestReply         ActorRef.ask(...).get()
  ActorRef.sendRequestReplyFuture   ActorRef.ask(...).get()
  ActorRef.replyUnsafe              ActorRef.reply
  ActorRef.replySafe                ActorRef.tryReply
  ActorRef.mailboxSize              ActorRef.dispatcher.mailboxSize(actorRef)
  ActorRef.!!                       ActorRef.?(...).as[T]
  ActorRef.!!!                      ActorRef.?
  ActorRef.reply\_?                 ActorRef.tryReply
  Future.receive                    Future.onResult
  Future.collect                    Future.map
  Future.failure                    Future.recover
  MessageDispatcher.pendingFutures  MessageDispatcher.tasks
  RemoteClientModule.*Listener(s)   EventHandler.<X>
  TestKit.expectMsg(pf)             TestKit.expectMsgPF
  TestKit.receiveWhile(pf)          TestKit.receiveWhile()(pf)
  ================================  =====================

Trivia
^^^^^^

This release contains changes to 221 files, with 16844 insertions and 4010
deletions (diff between two points, does not count multiple changes to the same
line). The authorship of the corresponding commits is distributed as shown
below; the listing should not be taken too seriously, though, it has just been
done using ``git log --shortstat`` and summing up the numbers, so it certainly
misses details like who originally authored changes which were then back-ported
from the master branch (do not fear, you will be correctly attributed when the
stats for 2.0 are made).

=======  ==========  =========  =========
Commits  Insertions  Deletions  Author
=======  ==========  =========  =========
     90       12315        245  Viktor Klang
     78        3823        200  Roland Kuhn
     41        9834        130  Patrik Nordwall
     31        1819        131  Peter Vlugter
      7         238         22  Derek Williams
      4          86         25  Peter Veentjer
      1          17          5  Debasish Ghosh
      2          15          5  Jonas Bonér
=======  ==========  =========  =========
  
.. note::

  Release notes of previous releases consisted of ticket or change listings in
  no particular order

Release 1.1
-----------

- **ADD** - #647 Extract an akka-camel-typed module out of akka-camel for optional typed actor support (Martin Krasser)
- **ADD** - #654 Allow consumer actors to acknowledge in-only message exchanges (Martin Krasser)
- **ADD** - #669 Support self.reply in preRestart and postStop after exception in receive (Martin Krasser)
- **ADD** - #682 Support for fault-tolerant Producer actors (Martin Krasser)
- **ADD** - Move TestKit to akka-testkit and add CallingThreadDispatcher (Roland Kuhn)
- **ADD** - Remote Client message buffering transaction log for buffering messages failed to send due to network problems. Flushes the buffer on reconnect. (Jonas Bonér)
- **ADD** - Added trait simulate network problems/errors to be used for remote actor testing (Jonas Bonér)
- **ADD** - Add future and await methods to Agent (Peter Vlugter)
- **ADD** - #586 Allow explicit reconnect for RemoteClient (Viktor Klang)
- **ADD** - #587 Dead letter sink queue for messages sent through RemoteClient that didn't get sent due to connection failure (Viktor Klang)
- **ADD** - #598 actor.id when using akka-spring should be the id of the spring bean (Viktor Klang)
- **ADD** - #652 Reap expired futures from ActiveRemoteClientHandler (Viktor Klang)
- **ADD** - #656 Squeeze more out of EBEDD? (Viktor Klang)
- **ADD** - #715 EventHandler.error should be usable without Throwable (Viktor Klang)
- **ADD** - #717 Add ExecutionHandler to NettyRemoteServer for more performance and scalability (Viktor Klang)
- **ADD** - #497 Optimize remote sends done in local scope (Viktor Klang)
- **ADD** - #633 Add support for Scalaz in akka-modules (Derek Williams)
- **ADD** - #677 Add map, flatMap, foreach, and filter to Future (Derek Williams)
- **ADD** - #661 Optimized Future's internals (Derek Williams)
- **ADD** - #685 Optimize execution of Futures (Derek Williams)
- **ADD** - #711 Make Future.completeWith work with an uncompleted Future (Derek Williams)
- **UPD** - #667 Upgrade to Camel 2.7.0 (Martin Krasser)
- **UPD** - Updated HawtDispatch to 1.1 (Hiram Chirino)
- **UPD** - #688 Update Akka 1.1-SNAPSHOT to Scala 2.9.0-RC1 (Viktor Klang)
- **UPD** - #718 Add HawtDispatcher to akka-modules (Viktor Klang)
- **UPD** - #698 Deprecate client-managed actors (Viktor Klang)
- **UPD** - #730 Update Akka and Akka Modules to SBT 0.7.6-RC0 (Viktor Klang)
- **UPD** - #663 Update to latest scalatest (Derek Williams)
- **FIX** - Misc cleanup, API changes and refactorings (Jonas Bonér)
- **FIX** - #675 preStart() is called twice when creating new instance of TypedActor (Debasish Ghosh)
- **FIX** - #704 Write docs for Java Serialization (Debasish Ghosh)
- **FIX** - #645 Change Futures.awaitAll to not throw FutureTimeoutException but return a List[Option[Any]] (Viktor Klang)
- **FIX** - #681 Clean exit using server-managed remote actor via client (Viktor Klang)
- **FIX** - #720 Connection loss when sending to a dead remote actor (Viktor Klang)
- **FIX** - #593 Move Jetty specific stuff (with deps) from akka-http to akka-kernel (Viktor Klang)
- **FIX** - #638 ActiveRemoteClientHandler - Unexpected exception from downstream in remote client (Viktor Klang)
- **FIX** - #655 Remote actors with non-uuid names doesnt work for req./reply-pattern (Viktor Klang)
- **FIX** - #588 RemoteClient.shutdown does not remove client from Map with clients (Viktor Klang)
- **FIX** - #672 Remoting breaks if mutual DNS lookup isn't possible (Viktor Klang)
- **FIX** - #699 Remote typed actor per-session server won't start if called method has no result (Viktor Klang)
- **FIX** - #702 Handle ReadTimeoutException in akka-remote (Viktor Klang)
- **FIX** - #708 Fall back to Akka classloader if event-handler class cannot be found. (Viktor Klang)
- **FIX** - #716 Split akka-http and clean-up dependencies (Viktor Klang)
- **FIX** - #721 Inability to parse/load the Config should do a System.exit(-1) (Viktor Klang)
- **FIX** - #722 Race condition in Actor hotswapping (Viktor Klang)
- **FIX** - #723 MessageSerializer CNFE regression (Viktor Klang)
- **FIX** - #680 Remote TypedActor behavior differs from local one when sending to generic interfaces (Viktor Klang)
- **FIX** - #659 Calling await on a Future that is expired and uncompleted should throw an exception (Derek Williams)
- **REM** - #626 Update and clean up dependencies (Viktor Klang)
- **REM** - #623 Remove embedded-repo (Akka + Akka Modules) (Viktor Klang)
- **REM** - #686 Remove SBinary (Viktor Klang)

Release 1.0-RC6
----------------------------------------

- **FIX** - #628 Supervied TypedActors fails to restart (Viktor Klang)
- **FIX** - #629 Stuck upon actor invocation (Viktor Klang)

Release 1.0-RC5
----------------------------------------

- **FIX** - Source JARs published to 'src' instead of 'source' || Odd Moller ||
- **FIX** - #612 Conflict between Spring autostart=true for Consumer actors and <akka:camel-service> (Martin Krasser)
- **FIX** - #613 Change Akka XML schema URI to http://akka.io/schema/akka (Martin Krasser)
- **FIX** - Spring XSD namespace changed from 'akkasource.org' to 'akka.io' (Viktor Klang)
- **FIX** - Checking for remote secure cookie is disabled by default if no akka.conf is loaded (Viktor Klang)
- **FIX** - Changed Casbah to ScalaToolsRepo for akka-sbt-plugin (Viktor Klang)
- **FIX** - ActorRef.forward now doesn't require the sender to be set on the message (Viktor Klang)

Release 1.0-RC3
----------------------------------------

- **ADD** - #568 Add autostart attribute to Spring actor configuration (Viktor Klang)
- **ADD** - #586 Allow explicit reconnect for remote clients (Viktor Klang)
- **ADD** - #587 Add possibility for dead letter queues for failed remote sends (Viktor Klang)
- **ADD** - #497 Optimize remote send in local scope (Viktor Klang)
- **ADD** - Improved Java Actor API: akka.actor.Actors (Viktor Klang)
- **ADD** - Improved Scala Actor API: akka.actor.Actor (Viktor Klang)
- **ADD** - #148 Create a testing framework for testing Actors (Roland Kuhn)
- **ADD** - Support Replica Set/Replica Pair connection modes with MongoDB Persistence || Brendan McAdams ||
- **ADD** - User configurable Write Concern settings for MongoDB Persistence || Brendan McAdams ||
- **ADD** - Support for configuring MongoDB Persistence with MongoDB's URI Connection String || Brendan McAdams ||
- **ADD** - Support for Authentication with MongoDB Persistence || Brendan McAdams ||
- **FIX** - Misc bug fixes || Team ||
- **FIX** - #603 Race condition in Remote send (Viktor Klang)
- **FIX** - #594 Log statement in RemoteClientHandler was wrongly formatted (Viktor Klang)
- **FIX** - #580 Message uuids must be generated (Viktor Klang)
- **FIX** - #583 Serialization classloader has a visibility issue (Viktor Klang)
- **FIX** - #598 By default the bean ID should become the actor id for Spring actor configuration (Viktor Klang)
- **FIX** - #577 RemoteClientHandler swallows certain exceptions (Viktor Klang)
- **FIX** - #581 Fix edgecase where an exception could not be deserialized (Viktor Klang)
- **FIX** - MongoDB write success wasn't being properly checked; fixed (integrated w/ new write concern features) || Brendan McAdams ||
- **UPD** - Improvements to FSM module akka.actor.FSM || Manie & Kuhn ||
- **UPD** - Changed Akka URI to http://akka.io. Reflects both XSDs, Maven repositories etc. (Jonas Bonér)
- **REM** - #574 Remote RemoteClient, RemoteServer and RemoteNode (Viktor Klang)
- **REM** - object UntypedActor, object ActorRegistry, class RemoteActor, class RemoteUntypedActor, class RemoteUntypedConsumerActor (Viktor Klang)

Release 1.0-RC1
----------------------------------------

- **ADD** - #477 Added support for Remote Agents (Viktor Klang)
- **ADD** - #460 Hotswap for Java API (UntypedActor) (Viktor Klang)
- **ADD** - #471 Added support for TypedActors to return Java Option (Viktor Klang)
- **ADD** - New design and API for more fluent and intuitive FSM module (Roland Kuhn)
- **ADD** - Added secure cookie based remote node authentication (Jonas Bonér)
- **ADD** - Untrusted safe mode for remote server (Jonas Bonér)
- **ADD** - Refactored config file format - added list of enabled modules etc. (Jonas Bonér)
- **ADD** - Docs for Dataflow Concurrency (Jonas Bonér)
- **ADD** - Made remote message frame size configurable (Jonas Bonér)
- **ADD** - #496 Detect when Remote Client disconnects (Jonas Bonér)
- **ADD** - #472 Improve API to wait for endpoint activation/deactivation (`more <migration-guide-0.10.x-1.0.x#await-activation>`__ ...) (Martin Krasser)
- **ADD** - #473 Allow consumer actors to customize their own routes (`more <Camel#intercepting-route-construction>`__ ...) (Martin Krasser)
- **ADD** - #504 Add session bound server managed remote actors || Paul Pach ||
- **ADD** - DSL for FSM (Irmo Manie)
- **ADD** - Shared unit test for all dispatchers to enforce Actor Model (Viktor Klang)
- **ADD** - #522 Make stacking optional for become and HotSwap (Viktor Klang)
- **ADD** - #524 Make frame size configurable for client&server (Bonér & Klang)
- **ADD** - #526 Add onComplete callback to Future (Viktor Klang)
- **ADD** - #536 Document Channel-abstraction for later replies (Viktor Klang)
- **ADD** - #540 Include self-reference as parameter to HotSwap (Viktor Klang)
- **ADD** - #546 Include Garrick Evans' Akka-mist into master (Viktor Klang)
- **ADD** - #438 Support remove operation in PersistentVector (Scott Clasen)
- **ADD** - #229 Memcached protocol support for Persistence module (Scott Clasen)
- **ADD** - Amazon SimpleDb support for Persistence module (Scott Clasen)
- **FIX** - #518 refactor common storage bakend to use bulk puts/gets where possible (Scott Clasen)
- **FIX** - #532 Prevent persistent datatypes with same uuid from corrupting a TX (Scott Clasen)
- **FIX** - #464 ThreadPoolBuilder should be rewritten to be an immutable builder (Viktor Klang)
- **FIX** - #449 Futures.awaitOne now uses onComplete listeners (Viktor Klang)
- **FIX** - #486 Fixed memory leak caused by Configgy that prevented full unload (Viktor Klang)
- **FIX** - #488 Fixed race condition in EBEDD restart (Viktor Klang)
- **FIX** - #492 Fixed race condition in Scheduler (Viktor Klang)
- **FIX** - #493 Switched to non-https repository for JBoss artifacts (Viktor Klang)
- **FIX** - #481 Exception when creating an actor now behaves properly when supervised (Viktor Klang)
- **FIX** - #498 Fixed no-op in supervision DSL (Viktor Klang)
- **FIX** - #491 ``reply`` and ``reply_?`` now sets a sender reference (Viktor Klang)
- **FIX** - #519 NotSerializableError when using Remote Typed Actors (Viktor Klang)
- **FIX** - #523 Message.toString is called all the time for incomign messages, expensive (Viktor Klang)
- **FIX** - #537 Make sure top folder is included in sources jar (Viktor Klang)
- **FIX** - #529 Remove Scala version number from Akka artifact ids (Viktor Klang)
- **FIX** - #533 Can't set LifeCycle from the Java API (Viktor Klang)
- **FIX** - #542 Make Future-returning Remote Typed Actor methods use onComplete (Viktor Klang)
- **FIX** - #479 Do not register listeners when CamelService is turned off by configuration (Martin Krasser)
- **FIX** - Fixed bug with finding TypedActor by type in ActorRegistry (Jonas Bonér)
- **FIX** - #515 race condition in FSM StateTimeout Handling (Irmo Manie)
- **UPD** - Akka package from "se.scalablesolutions.akka" to "akka" (Viktor Klang)
- **UPD** - Update Netty to 3.2.3.Final (Viktor Klang)
- **UPD** - #458 Camel to 2.5.0 (Martin Krasser)
- **UPD** - #458 Spring to 3.0.4.RELEASE (Martin Krasser)
- **UPD** - #458 Jetty to 7.1.6.v20100715 (Martin Krasser)
- **UPD** - Update to Scala 2.8.1 (Jonas Bonér)
- **UPD** - Changed remote server default port to 2552 (AKKA) (Jonas Bonér)
- **UPD** - Cleaned up and made remote protocol more effifient (Jonas Bonér)
- **UPD** - #528 RedisPersistentRef should not throw in case of missing key (Debasish Ghosh)
- **UPD** - #531 Fix RedisStorage add() method in Java API (Debasish Ghosh)
- **UPD** - #513 Implement snapshot based persistence control in SortedSet (Debasish Ghosh)
- **UPD** - #547 Update FSM docs (Irmo Manie)
- **UPD** - #548 Update AMQP docs (Irmo Manie)
- **REM** - Atmosphere integration, replace with Mist (Klang @ Evans)
- **REM** - JGroups integration, doesn't play with cloud services :/ (Viktor Klang)

Release 1.0-MILESTONE1
----------------------------------------

- **ADD** - Splitted akka-core up in akka-actor, akka-typed-actor & akka-remote (Jonas Bonér)
- **ADD** - Added meta-data to network protocol (Jonas Bonér)
- **ADD** - HotSwap and actor.become now uses a stack of PartialFunctions with API for pushing and popping the stack (Jonas Bonér)
- **ADD** - #440 Create typed actors with constructor args (Michael Kober)
- **ADD** - #322 Abstraction for unification of sender and senderFuture for later reply (Michael Kober)
- **ADD** - #364 Serialization for TypedActor proxy reference (Michael Kober)
- **ADD** - #423 Support configuration of Akka via Spring (Michael Kober)
- **FIX** - #426 UUID wrong for remote proxy for server managed actor (Michael Kober)
- **ADD** - #378 Support for server initiated remote TypedActor and UntypedActor in Spring config (Michael Kober)
- **ADD** - #194 Support for server-managed typed actor ||< Michael Kober ||
- **ADD** - #447 Allow Camel service to be turned off by configuration (Martin Krasser)
- **ADD** - #457 JavaAPI improvements for akka-camel (please read the `migration guide <migration-guide-0.10.x-1.0.x#akka-camel>`_) (Martin Krasser)
- **ADD** - #465 Dynamic message routing to actors (`more <Camel#actor-component>`__ ...) (Martin Krasser)
- **FIX** - #410 Use log configuration from config directory (Martin Krasser)
- **FIX** - #343 Some problems with persistent structures (Debasish Ghosh)
- **FIX** - #430 Refactor / re-implement MongoDB adapter so that it conforms to the guidelines followed in Redis and Cassandra modules (Debasish Ghosh)
- **FIX** - #436 ScalaJSON serialization does not map Int data types properly when used within a Map (Debasish Ghosh)
- **ADD** - #230 Update redisclient to be Redis 2.0 compliant (Debasish Ghosh)
- **FIX** - #435 Mailbox serialization does not retain messages (Debasish Ghosh)
- **ADD** - #445 Integrate type class based serialization of sjson into Akka (Debasish Ghosh)
- **FIX** - #480: Regression multibulk replies redis client (Debasish Ghosh)
- **FIX** - #415 Publish now generate source and doc jars (Viktor Klang)
- **FIX** - #420 REST endpoints should be able to be processed in parallel (Viktor Klang)
- **FIX** - #422 Dispatcher config should work for ThreadPoolBuilder-based dispatchers (Viktor Klang)
- **FIX** - #401 ActorRegistry should not leak memory (Viktor Klang)
- **FIX** - #250 Performance optimization for ExecutorBasedEventDrivenDispatcher (Viktor Klang)
- **FIX** - #419 Rename init and shutdown callbacks to preStart and postStop, and remove initTransactionalState (Viktor Klang)
- **FIX** - #346 Make max no of restarts (and within) are now both optional (Viktor Klang)
- **FIX** - #424 Actors self.supervisor not set by the time init() is called when started by startLink() (Viktor Klang)
- **FIX** - #427 spawnLink and startLink now has the same dispatcher semantics (Viktor Klang)
- **FIX** - #413 Actor shouldn't process more messages when waiting to be restarted (HawtDispatcher still does) (Viktor Klang)
- **FIX** - !! and !!! now do now not block the actor when used in remote actor (Viktor Klang)
- **FIX** - RemoteClient now reconnects properly (Viktor Klang)
- **FIX** - Logger.warn now properly works with varargs (Viktor Klang)
- **FIX** - #450 Removed ActorRef lifeCycle boilerplate: Some(LifeCycle(Permanent)) => Permanent (Viktor Klang)
- **FIX** - Moved ActorRef.trapExit into ActorRef.faultHandler and removed Option-boilerplate from faultHandler (Viktor Klang)
- **FIX** - ThreadBasedDispatcher cheaper for idling actors, also benefits from all that is ExecutorBasedEventDrivenDispatcher (Viktor Klang)
- **FIX** - Fixing Futures.future, uses Actor.spawn under the hood, specify dispatcher to control where block is executed (Viktor Klang)
- **FIX** - #469 Akka "dist" now uses a root folder to avoid loitering if unzipped in a folder (Viktor Klang)
- **FIX** - Removed ScalaConfig, JavaConfig and rewrote Supervision configuration (Viktor Klang)
- **UPD** - Jersey to 1.3 (Viktor Klang)
- **UPD** - Atmosphere to 0.6.2 (Viktor Klang)
- **UPD** - Netty to 3.2.2.Final (Viktor Klang)
- **ADD** - Changed config file priority loading and added config modes. (Viktor Klang)
- **ADD** - #411 Bumped Jetty to v 7 and migrated to it's eclipse packages (Viktor Klang)
- **ADD** - #414 Migrate from Grizzly to Jetty for Akka Microkernel (Viktor Klang)
- **ADD** - #261 Add Java API for 'routing' module (Viktor Klang)
- **ADD** - #262 Add Java API for Agent (Viktor Klang)
- **ADD** - #264 Add Java API for Dataflow (Viktor Klang)
- **ADD** - Using JerseySimpleBroadcaster instead of JerseyBroadcaster in AkkaBroadcaster (Viktor Klang)
- **ADD** - #433 Throughput deadline added for ExecutorBasedEventDrivenDispatcher (Viktor Klang)
- **ADD** - Add possibility to set default cometSupport in akka.conf (Viktor Klang)
- **ADD** - #451 Added possibility to use akka-http as a standalone REST server (Viktor Klang)
- **ADD** - #446 Added support for Erlang-style receiveTimeout (Viktor Klang)
- **ADD** - #462 Added support for suspend/resume of processing individual actors mailbox, should give clearer restart semantics (Viktor Klang)
- **ADD** - #466 Actor.spawn now takes an implicit dispatcher to specify who should run the block (Viktor Klang)
- **ADD** - #456 Added map to Future and Futures.awaitMap (Viktor Klang)
- **REM** - #418 Remove Lift sample module and docs (Viktor Klang)
- **REM** - Removed all Reactor-based dispatchers (Viktor Klang)
- **REM** - Removed anonymous actor factories (Viktor Klang)
- **ADD** - Voldemort support for akka-persistence (Scott Clasen)
- **ADD** - HBase support for akka-persistence (David Greco)
- **ADD** - CouchDB support for akka-persistence (Yung-Luen Lan & Kahlen)
- **ADD** - #265 Java API for AMQP module (Irmo Manie)

Release 0.10 - Aug 21 2010
----------------------------------------

- **ADD** - Added new Actor type: UntypedActor for Java API (Jonas Bonér)
- **ADD** - #26 Deep serialization of Actor including its mailbox (Jonas Bonér)
- **ADD** - Rewritten network protocol. More efficient and cleaner. (Jonas Bonér)
- **ADD** - Rewritten Java Active Object tests into Scala to be able to run the in SBT. (Jonas Bonér)
- **ADD** - Added isDefinedAt method to Actor for checking if it can receive a certain message (Jonas Bonér)
- **ADD** - Added caching of Active Object generated class bytes, huge perf improvement (Jonas Bonér)
- **ADD** - Added RemoteClient Listener API (Jonas Bonér)
- **ADD** - Added methods to retrieve children from a Supervisor (Jonas Bonér)
- **ADD** - Rewritten Supervisor to become more clear and "correct" (Jonas Bonér)
- **ADD** - Added options to configure a blocking mailbox with custom capacity (Jonas Bonér)
- **ADD** - Added RemoteClient reconnection time window configuration option (Jonas Bonér)
- **ADD** - Added ActiveObjectContext with sender reference etc (Jonas Bonér)
- **ADD** - #293 Changed config format to JSON-style (Jonas Bonér)
- **ADD** - #302: Incorporate new ReceiveTimeout in Actor serialization (Jonas Bonér)
- **ADD** - Added Java API docs and made it comparable with Scala API docs. 1-1 mirroring (Jonas Bonér)
- **ADD** - Renamed Active Object to Typed Actor (Jonas Bonér)
- **ADD** - Enhanced Typed Actor: remoting, "real" restart upon failure etc. (Jonas Bonér)
- **ADD** - Typed Actor now inherits Actor and is a full citizen in the Actor world. (Jonas Bonér)
- **ADD** - Added support for remotely shutting down a remote actor (Jonas Bonér)
- **ADD** - #224 Add support for Camel in typed actors (`more <Camel#typed-actor>`__ ...) (Martin Krasser)
- **ADD** - #282 Producer trait should implement Actor.receive (`more <Camel#produce>`__...) (Martin Krasser)
- **ADD** - #271 Support for bean scope prototype in akka-spring (Johan Rask)
- **ADD** - Support for DI of values and bean references on target instance in akka-spring (Johan Rask)
- **ADD** - #287 Method annotated with @postrestart in ActiveObject is not called during restart (Johan Rask)
- **ADD** - Support for ApplicationContextAware in akka-spring (Johan Rask)
- **ADD** - #199 Support shutdown hook in TypedActor (Martin Krasser)
- **ADD** - #266 Access to typed actors from user-defined Camel routes (`more <Camel#access-typed-actors>`__ ...) (Martin Krasser)
- **ADD** - #268 Revise akka-camel documentation (`more <Camel>`__ ...) (Martin Krasser)
- **ADD** - #289 Support for <akka:camel-service> Spring configuration element (`more <Camel#spring-applications>`__ ...) (Martin Krasser)
- **ADD** - #296 TypedActor lifecycle management (Martin Krasser)
- **ADD** - #297 Shutdown routes to typed actors (`more <Camel#unpublishing-typed-actor>`__ ...) (Martin Krasser)
- **ADD** - #314 akka-spring to support typed actor lifecycle management (`more <spring-integration#stop>`__ ...) (Martin Krasser)
- **ADD** - #315 akka-spring to support configuration of shutdown callback method (`more <spring-integration#supervisor-configuration>`__ ...) (Martin Krasser)
- **ADD** - Fault-tolerant consumer actors and typed consumer actors (`more <Camel#fault-tolerance>`__ ...) (Martin Krasser)
- **ADD** - #320 Leverage Camel's non-blocking routing engine (`more <Camel#async-routing>`__ ...) (Martin Krasser)
- **ADD** - #335 Producer trait should allow forwarding of results (Martin Krasser)
- **ADD** - #339 Redesign of Producer trait (pre/post processing hooks, async in-out) (`more <Camel#pre-post-processing>`__ ...) (Martin Krasser)
- **ADD** - Non-blocking, asynchronous routing example for akka-camel (`more <Camel#non-blocking-example>`__ ...) (Martin Krasser)
- **ADD** - #333 Allow applications to wait for endpoints being activated (`more <Camel#await-completion>`__ ...) (Martin Krasser)
- **ADD** - #356 Support @consume annotations on typed actor implementation class (Martin Krasser)
- **ADD** - #357 Support untyped Java actors as endpoint consumer (Martin Krasser)
- **ADD** - #366 CamelService should be a singleton (Martin Krasser)
- **ADD** - #392 Support untyped Java actors as endpoint producer (Martin Krasser)
- **ADD** - #393 Redesign CamelService singleton to be a CamelServiceManager (`more <Camel#consumers-and-camel-service>`__ ...) (Martin Krasser)
- **ADD** - #295 Refactoring Actor serialization to type classes (Debasish Ghosh)
- **ADD** - #317 Change documentation for Actor Serialization (Debasish Ghosh)
- **ADD** - #388 Typeclass serialization of ActorRef/UntypedActor isn't Java friendly (Debasish Ghosh)
- **ADD** - #292 Add scheduleOnce to Scheduler (Irmo Manie)
- **ADD** - #308 Initial receive timeout on actor (Irmo Manie)
- **ADD** - Redesign of AMQP module (`more <amqp>`__ ...) (Irmo Manie)
- **ADD** - Added "become(behavior: Option[Receive])" to Actor (Viktor Klang)
- **ADD** - Added "find[T](f: PartialFunction[ActorRef,T]) : Option[T]" to ActorRegistry (Viktor Klang)
- **ADD** - #369 Possibility to configure dispatchers in akka.conf (Viktor Klang)
- **ADD** - #395 Create ability to add listeners to RemoteServer (Viktor Klang)
- **ADD** - #225 Add possibility to use Scheduler from TypedActor (Viktor Klang)
- **ADD** - #61 Integrate new persistent datastructures in Scala 2.8 (Peter Vlugter)
- **ADD** - Expose more of what Multiverse can do (Peter Vlugter)
- **ADD** - #205 STM transaction settings (Peter Vlugter)
- **ADD** - #206 STM transaction deferred and compensating (Peter Vlugter)
- **ADD** - #232 Expose blocking transactions (Peter Vlugter)
- **ADD** - #249 Expose Multiverse Refs for primitives (Peter Vlugter)
- **ADD** - #390 Expose transaction propagation level in multiverse (Peter Vlugter)
- **ADD** - Package objects for importing local/global STM (Peter Vlugter)
- **ADD** - Java API for the STM (Peter Vlugter)
- **ADD** - #379 Create STM Atomic templates for Java API (Peter Vlugter)
- **ADD** - #270 SBT plugin for Akka (Peter Vlugter)
- **ADD** - #198 support for ThreadBasedDispatcher in Spring config (Michael Kober)
- **ADD** - #377 support HawtDispatcher in Spring config (Michael Kober)
- **ADD** - #376 support Spring config for untyped actors (Michael Kober)
- **ADD** - #200 support WorkStealingDispatcher in Spring config (Michael Kober)
- **UPD** - #336 RabbitMQ 1.8.1 (Irmo Manie)
- **UPD** - #288 Netty to 3.2.1.Final (Viktor Klang)
- **UPD** - Atmosphere to 0.6.1 (Viktor Klang)
- **UPD** - Lift to 2.8.0-2.1-M1 (Viktor Klang)
- **UPD** - Camel to 2.4.0 (Martin Krasser)
- **UPD** - Spring to 3.0.3.RELEASE (Martin Krasser)
- **UPD** - Multiverse to 0.6 (Peter Vlugter)
- **FIX** - Fixed bug with stm not being enabled by default when no AKKA_HOME is set (Jonas Bonér)
- **FIX** - Fixed bug in network manifest serialization (Jonas Bonér)
- **FIX** - Fixed bug Remote Actors (Jonas Bonér)
- **FIX** - Fixed memory leak in Active Objects (Jonas Bonér)
- **FIX** - Fixed indeterministic deadlock in Transactor restart (Jonas Bonér)
- **FIX** - #325 Fixed bug in STM with dead hanging CountDownCommitBarrier (Jonas Bonér)
- **FIX** - #316: NoSuchElementException during ActiveObject restart (Jonas Bonér)
- **FIX** - #256: Tests for ActiveObjectContext (Jonas Bonér)
- **FIX** - Fixed bug in restart of Actors with 'Temporary' life-cycle (Jonas Bonér)
- **FIX** - #280 Tests fail if there is no akka.conf set (Jonas Bonér)
- **FIX** - #286 unwanted transitive dependencies from Geronimo project (Viktor Klang)
- **FIX** - Atmosphere comet comment to use stream instead of writer (Viktor Klang)
- **FIX** - #285 akka.conf is now used as defaults for Akka REST servlet init parameters (Viktor Klang)
- **FIX** - #321 fixed performance regression in ActorRegistry (Viktor Klang)
- **FIX** - #286 geronimo servlet 2.4 dep is no longer transitively loaded (Viktor Klang)
- **FIX** - #334 partial lift sample rewrite to fix breakage (Viktor Klang)
- **FIX** - Fixed a memory leak in ActorRegistry (Viktor Klang)
- **FIX** - Fixed a race-condition in Cluster (Viktor Klang)
- **FIX** - #355 Switched to Array instead of List on ActorRegistry return types (Viktor Klang)
- **FIX** - #352 ActorRegistry.actorsFor(class) now checks isAssignableFrom (Viktor Klang)
- **FIX** - Fixed a race condition in ActorRegistry.register (Viktor Klang)
- **FIX** - #337 Switched from Configgy logging to SLF4J, better for OSGi (Viktor Klang)
- **FIX** - #372 Scheduler now returns Futures to cancel tasks (Viktor Klang)
- **FIX** - #306 JSON serialization between remote actors is not transparent (Debasish Ghosh)
- **FIX** - #204 Reduce object creation in STM (Peter Vlugter)
- **FIX** - #253 Extend Multiverse BasicRef rather than wrap ProgrammaticRef (Peter Vlugter)
- **REM** - Removed pure POJO-style Typed Actor (old Active Object) (Jonas Bonér)
- **REM** - Removed Lift as a dependency for Akka-http (Viktor Klang)
- **REM** - #294 Remove ``reply`` and ``reply_?`` from Actor (Viktor Klang)
- **REM** - Removed one field in Actor, should be a minor memory reduction for high actor quantities (Viktor Klang)
- **FIX** - #301 DI does not work in akka-spring when specifying an interface (Johan Rask)
- **FIX** - #328 trapExit should pass through self with Exit to supervisor (Irmo Manie)
- **FIX** - Fixed warning when deregistering listeners (Martin Krasser)
- **FIX** - Added camel-jetty-2.4.0.1 to Akka's embedded-repo. (fixes a concurrency bug in camel-jetty-2.4.0, to be officially released in Camel 2.5.0) (Martin Krasser)
- **FIX** - #338 RedisStorageBackend fails when redis closes connection to idle client (Debasish Ghosh)
- **FIX** - #340 RedisStorage Map.get does not throw exception when disconnected from redis but returns None (Debasish Ghosh)

Release 0.9 - June 2th 2010
----------------------------------------

- **ADD** - Serializable, immutable, network-aware ActorRefs (Jonas Bonér)
- **ADD** - Optionally JTA-aware STM transactions (Jonas Bonér)
- **ADD** - Rewritten supervisor management, making use of ActorRef, now really kills the Actor instance and replaces it (Jonas Bonér)
- **ADD** - Allow linking and unlinking a declaratively configured Supervisor (Jonas Bonér)
- **ADD** - Remote protocol rewritten to allow passing along sender reference in all situations (Jonas Bonér)
- **ADD** - #37 API for JTA usage (Jonas Bonér)
- **ADD** - Added user accessible 'sender' and 'senderFuture' references (Jonas Bonér)
- **ADD** - Sender actor is now passed along for all message send functions (!, !!, !!!, forward) (Jonas Bonér)
- **ADD** - Subscription API for listening to RemoteClient failures (Jonas Bonér)
- **ADD** - Implemented link/unlink for ActiveObjects || Jan Kronquist / Michael Kober ||
- **ADD** - Added alter method to TransactionalRef + added appl(initValue) to Transactional Map/Vector/Ref (Peter Vlugter)
- **ADD** - Load dependency JARs in JAR deloyed in kernel's ,/deploy dir (Jonas Bonér)
- **ADD** - Allowing using Akka without specifying AKKA_HOME or path to akka.conf config file (Jonas Bonér)
- **ADD** - Redisclient now supports PubSub (Debasish Ghosh)
- **ADD** - Added a sample project under akka-samples for Redis PubSub using Akka actors (Debasish Ghosh)
- **ADD** - Richer API for Actor.reply (Viktor Klang)
- **ADD** - Added Listeners to Akka patterns (Viktor Klang)
- **ADD** - #183 Deactivate endpoints of stopped consumer actors (Martin Krasser)
- **ADD** - Camel `Message API improvements <migration-guide-0.8.x-0.9.x#camel>`_ (Martin Krasser)
- **ADD** - #83 Send notification to parent supervisor if all actors supervised by supervisor has been permanently killed (Jonas Bonér)
- **ADD** - #121 Make it possible to dynamically create supervisor hierarchies for Active Objects (Michael Kober)
- **ADD** - #131 Subscription API for node joining & leaving cluster (Jonas Bonér)
- **ADD** - #145 Register listener for errors in RemoteClient/RemoteServer (Jonas Bonér)
- **ADD** - #146 Create an additional distribution with sources (Jonas Bonér)
- **ADD** - #149 Support loading JARs from META-INF/lib in JARs put into the ./deploy directory (Jonas Bonér)
- **ADD** - #166 Implement insertVectorStorageEntriesFor in CassandraStorageBackend (Jonas Bonér)
- **ADD** - #168 Separate ID from Value in Actor; introduce ActorRef (Jonas Bonér)
- **ADD** - #174 Create sample module for remote actors (Jonas Bonér)
- **ADD** - #175 Add new sample module with Peter Vlugter's Ant demo (Jonas Bonér)
- **ADD** - #177 Rewrite remote protocol to make use of new ActorRef (Jonas Bonér)
- **ADD** - #180 Make use of ActorRef indirection for fault-tolerance management (Jonas Bonér)
- **ADD** - #184 Upgrade to Netty 3.2.0.CR1 (Jonas Bonér)
- **ADD** - #185 Rewrite Agent and Supervisor to work with new ActorRef (Jonas Bonér)
- **ADD** - #188 Change the order of how the akka.conf is detected (Jonas Bonér)
- **ADD** - #189 Reintroduce 'sender: Option[Actor]' ref in Actor (Jonas Bonér)
- **ADD** - #203 Upgrade to Scala 2.8 RC2 (Jonas Bonér)
- **ADD** - #222 Using Akka without AKKA_HOME or akka.conf (Jonas Bonér)
- **ADD** - #234 Add support for injection and management of ActiveObjectContext with RTTI such as 'sender' and 'senderFuture' references etc. (Jonas Bonér)
- **ADD** - #236 Upgrade SBinary to Scala 2.8 RC2 (Jonas Bonér)
- **ADD** - #235 Problem with RedisStorage.getVector(..) data structure storage management (Jonas Bonér)
- **ADD** - #239 Upgrade to Camel 2.3.0 (Martin Krasser)
- **ADD** - #242 Upgraded to Scala 2.8 RC3 (Jonas Bonér)
- **ADD** - #243 Upgraded to Protobuf 2.3.0 (Jonas Bonér)
- **ADD** - Added option to specify class loader when de-serializing messages and RemoteActorRef in RemoteClient (Jonas Bonér)
- **ADD** - #238 Upgrading to Cassandra 0.6.1 (Jonas Bonér)
- **ADD** - Upgraded to Jersey 1.2 (Viktor Klang)
- **ADD** - Upgraded Atmosphere to 0.6-SNAPSHOT, adding WebSocket support (Viktor Klang)
- **FIX** - Simplified ActiveObject configuration (Michael Kober)
- **FIX** - #237 Upgrade Mongo Java driver to 1.4 (the latest stable release) (Debasish Ghosh)
- **FIX** - #165 Implemented updateVectorStorageEntryFor in Mongo persistence module (Debasish Ghosh)
- **FIX** - #154: Allow ActiveObjects to use the default timeout in config file (Michael Kober)
- **FIX** - Active Object methods with @inittransactionalstate should be invoked automatically (Michael Kober)
- **FIX** - Nested supervisor hierarchy failure propagation bug fixed (Jonas Bonér)
- **FIX** - Fixed bug on CommitBarrier transaction registration (Jonas Bonér)
- **FIX** - Merged many modules to reduce total number of modules (Viktor Klang)
- **FIX** - Future parameterized (Viktor Klang)
- **FIX** - #191: Workstealing dispatcher didn't work with !! (Viktor Klang)
- **FIX** - #202: Allow applications to disable stream-caching (Martin Krasser)
- **FIX** - #119 Problem with Cassandra-backed Vector (Jonas Bonér)
- **FIX** - #147 Problem replying to remote sender when message sent with ! (Jonas Bonér)
- **FIX** - #171 initial value of Ref can become null if first transaction rolled back (Jonas Bonér)
- **FIX** - #172 Fix "broken" Protobuf serialization API (Jonas Bonér)
- **FIX** - #173 Problem with Vector::slice in CassandraStorage (Jonas Bonér)
- **FIX** - #190 RemoteClient shutdown ends up in endless loop (Jonas Bonér)
- **FIX** - #211 Problem with getting CommitBarrierOpenException when using Transaction.Global (Jonas Bonér)
- **FIX** - #240 Supervised actors not started when starting supervisor (Jonas Bonér)
- **FIX** - Fixed problem with Transaction.Local not committing to persistent storage (Jonas Bonér)
- **FIX** - #215: Re-engineered the JAX-RS support (Viktor Klang)
- **FIX** - Many many bug fixes || Team ||
- **REM** - Shoal cluster module (Viktor Klang)

Release 0.8.1 - April 6th 2010
----------------------------------------

- **ADD** - Redis cluster support (Debasish Ghosh)
- **ADD** - Reply to remote sender from message set with ! (Jonas Bonér)
- **ADD** - Load-balancer which prefers actors with few messages in mailbox || Jan Van Besien ||
- **ADD** - Added developer mailing list: [akka-dev AT googlegroups DOT com] (Jonas Bonér)
- **FIX** - Separated thread-local from thread-global transaction API (Jonas Bonér)
- **FIX** - Fixed bug in using STM outside Actors (Jonas Bonér)
- **FIX** - Fixed bug in anonymous actors (Jonas Bonér)
- **FIX** - Moved web initializer to new akka-servlet module (Viktor Klang)

Release 0.8 - March 31st 2010
----------------------------------------

- **ADD** - Scala 2.8 based (Viktor Klang)
- **ADD** - Monadic API for Agents (Jonas Bonér)
- **ADD** - Agents are transactional (Jonas Bonér)
- **ADD** - Work-stealing dispatcher || Jan Van Besien ||
- **ADD** - Improved Spring integration (Michael Kober)
- **FIX** - Various bugfixes || Team ||
- **FIX** - Improved distribution packaging (Jonas Bonér)
- **REMOVE** - Actor.send function (Jonas Bonér)

Release 0.7 - March 21st 2010
----------------------------------------

- **ADD** - Rewritten STM now works generically with fire-forget message flows (Jonas Bonér)
- **ADD** - Apache Camel integration (Martin Krasser)
- **ADD** - Spring integration (Michael Kober)
- **ADD** - Server-managed Remote Actors (Jonas Bonér)
- **ADD** - Clojure-style Agents (Viktor Klang)
- **ADD** - Shoal cluster backend (Viktor Klang)
- **ADD** - Redis-based transactional queue storage backend (Debasish Ghosh)
- **ADD** - Redis-based transactional sorted set storage backend (Debasish Ghosh)
- **ADD** - Redis-based atomic INC (index) operation (Debasish Ghosh)
- **ADD** - Distributed Comet (Viktor Klang)
- **ADD** - Project moved to SBT (simple-build-tool) || Peter Hausel ||
- **ADD** - Futures object with utility methods for Future's (Jonas Bonér)
- **ADD** - !!! function that returns a Future (Jonas Bonér)
- **ADD** - Richer ActorRegistry API (Jonas Bonér)
- **FIX** - Improved event-based dispatcher performance with 40% || Jan Van Besien ||
- **FIX** - Improved remote client pipeline performance (Viktor Klang)
- **FIX** - Support several Clusters on the same network (Viktor Klang)
- **FIX** - Structural package refactoring (Jonas Bonér)
- **FIX** - Various bugs fixed || Team ||

Release 0.6 - January 5th 2010
----------------------------------------

- **ADD** - Clustered Comet using Akka remote actors and clustered membership API (Viktor Klang)
- **ADD** - Cluster membership API and implementation based on JGroups (Viktor Klang)
- **ADD** - Security module for HTTP-based authentication and authorization (Viktor Klang)
- **ADD** - Support for using Scala XML tags in RESTful Actors (scala-jersey) (Viktor Klang)
- **ADD** - Support for Comet Actors using Atmosphere (Viktor Klang)
- **ADD** - MongoDB as Akka storage backend (Debasish Ghosh)
- **ADD** - Redis as Akka storage backend (Debasish Ghosh)
- **ADD** - Transparent JSON serialization of Scala objects based on SJSON (Debasish Ghosh)
- **ADD** - Kerberos/SPNEGO support for Security module || Eckhart Hertzler ||
- **ADD** - Implicit sender for remote actors: Remote actors are able to use reply to answer a request || Mikael Högqvist ||
- **ADD** - Support for using the Lift Web framework with Actors || Tim Perrett ||
- **ADD** - Added CassandraSession API (with socket pooling) wrapping Cassandra's Thrift API in Scala and Java APIs (Jonas Bonér)
- **ADD** - Rewritten STM, now integrated with Multiverse STM (Jonas Bonér)
- **ADD** - Added STM API for atomic {..} and run {..} orElse {..} (Jonas Bonér)
- **ADD** - Added STM retry (Jonas Bonér)
- **ADD** - AMQP integration; abstracted as actors in a supervisor hierarchy. Impl AMQP 0.9.1 (Jonas Bonér)
- **ADD** - Complete rewrite of the persistence transaction management, now based on Unit of Work and Multiverse STM (Jonas Bonér)
- **ADD** - Monadic API to TransactionalRef (use it in for-comprehension) (Jonas Bonér)
- **ADD** - Lightweight actor syntax using one of the Actor.actor(..) methods. F.e: 'val a = actor { case _ => .. }' (Jonas Bonér)
- **ADD** - Rewritten event-based dispatcher which improved perfomance by 10x, now substantially faster than event-driven Scala Actors (Jonas Bonér)
- **ADD** - New Scala JSON parser based on sjson (Jonas Bonér)
- **ADD** - Added zlib compression to remote actors (Jonas Bonér)
- **ADD** - Added implicit sender reference for fire-forget ('!') message sends (Jonas Bonér)
- **ADD** - Monadic API to TransactionalRef (use it in for-comprehension) (Jonas Bonér)
- **ADD** - Smoother web app integration; just add akka.conf to the classpath (WEB-INF/classes), no need for AKKA_HOME or -Dakka.conf=.. (Jonas Bonér)
- **ADD** - Modularization of distribution into a thin core (actors, remoting and STM) and the rest in submodules (Jonas Bonér)
- **ADD** - Added 'forward' to Actor, forwards message but keeps original sender address (Jonas Bonér)
- **ADD** - JSON serialization for Java objects (using Jackson) (Jonas Bonér)
- **ADD** - JSON serialization for Scala objects (using SJSON) (Jonas Bonér)
- **ADD** - Added implementation for remote actor reconnect upon failure (Jonas Bonér)
- **ADD** - Protobuf serialization for Java and Scala objects (Jonas Bonér)
- **ADD** - SBinary serialization for Scala objects (Jonas Bonér)
- **ADD** - Protobuf as remote protocol (Jonas Bonér)
- **ADD** - Updated Cassandra integration and CassandraSession API to v0.4 (Jonas Bonér)
- **ADD** - CassandraStorage is now works with external Cassandra cluster (Jonas Bonér)
- **ADD** - ActorRegistry for retrieving Actor instances by class name and by id (Jonas Bonér)
- **ADD** - SchedulerActor for scheduling periodic tasks (Jonas Bonér)
- **ADD** - Now start up kernel with 'java -jar dist/akka-0.6.jar' (Jonas Bonér)
- **ADD** - Added Akka user mailing list: akka-user AT googlegroups DOT com]] (Jonas Bonér)
- **ADD** - Improved and restructured documentation (Jonas Bonér)
- **ADD** - New URL: http://akkasource.org (Jonas Bonér)
- **ADD** - New and much improved docs (Jonas Bonér)
- **ADD** - Enhanced trapping of failures: 'trapExit = List(classOf[..], classOf[..])' (Jonas Bonér)
- **ADD** - Upgraded to Netty 3.2, Protobuf 2.2, ScalaTest 1.0, Jersey 1.1.3, Atmosphere 0.4.1, Cassandra 0.4.1, Configgy 1.4 (Jonas Bonér)
- **FIX** - Lowered actor memory footprint; now an actor consumes ~600 bytes, which mean that you can create 6.5 million on 4 GB RAM (Jonas Bonér)
- **FIX** - Remote actors are now defined by their UUID (not class name) (Jonas Bonér)
- **FIX** - Fixed dispatcher bugs (Jonas Bonér)
- **FIX** - Cleaned up Maven scripts and distribution in general (Jonas Bonér)
- **FIX** - Fixed many many bugs and minor issues (Jonas Bonér)
- **FIX** - Fixed inconsistencies and uglyness in Actors API (Jonas Bonér)
- **REMOVE** - Removed concurrent mode (Jonas Bonér)
- **REMOVE** - Removed embedded Cassandra mode (Jonas Bonér)
- **REMOVE** - Removed the !? method in Actor (synchronous message send, since it's evil. Use !! with time-out instead. (Jonas Bonér)
- **REMOVE** - Removed startup scripts and lib dir (Jonas Bonér)
- **REMOVE** - Removed the 'Transient' life-cycle scope since to close to 'Temporary' in semantics. (Jonas Bonér)
- **REMOVE** - Removed 'Transient' Actors and restart timeout (Jonas Bonér)
