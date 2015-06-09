.. _migration-2.4:

##############################
Migration Guide 2.3.x to 2.4.x
##############################

The 2.4 release contains some structural changes that require some
simple, mechanical source-level changes in client code.

When migrating from earlier versions you should first follow the instructions for
migrating :ref:`1.3.x to 2.0.x <migration-2.0>` and then :ref:`2.0.x to 2.1.x <migration-2.1>`
and then :ref:`2.1.x to 2.2.x <migration-2.2>` and then :ref:`2.2.x to 2.3.x <migration-2.3>`.

Binary Compatibility
====================

Akka 2.4.x is backwards binary compatible with previous 2.3.x versions apart from the following
exceptions. This means that the new JARs are a drop-in replacement for the old one 
(but not the other way around) as long as your build does not enable the inliner (Scala-only restriction).

The following parts are not binary compatible with 2.3.x:

* akka-testkit and akka-remote-testkit
* experimental modules, such as akka-persistence and akka-contrib
* features, classes, methods that were deprecated in 2.3.x and removed in 2.4.x 

The dependency to **Netty** has been updated from version 3.8.0.Final to 3.10.3.Final. The changes in 
those versions might not be fully binary compatible, but we believe that it will not be a problem
in practice. No changes were needed to the Akka source code for this update. Users of libraries that
depend on 3.8.0.Final that break with 3.10.3.Final should be able to manually downgrade the dependency
to 3.8.0.Final and Akka will still work with that version.

Advanced Notice: TypedActors will go away
=========================================

While technically not yet deprecated, the current ``akka.actor.TypedActor`` support will be superseded by
the Akka Typed project that is currently being developed in open preview mode. If you are using TypedActors
in your projects you are advised to look into this, as it is superior to the Active Object pattern expressed
in TypedActors. The generic ActorRefs in Akka Typed allow the same type-safety that is afforded by
TypedActors while retaining all the other benefits of an explicit actor model (including the ability to
change behaviors etc.).

It is likely that TypedActors will be officially deprecated in the next major update of Akka and subsequently removed.

TestKit.remaining throws AssertionError
=======================================

In earlier versions of Akka `TestKit.remaining` returned the default timeout configurable under
"akka.test.single-expect-default". This was a bit confusing and thus it has been changed to throw an
AssertionError if called outside of within. The old behavior however can still be achieved by
calling `TestKit.remainingOrDefault` instead.

EventStream and ManagedActorClassification EventBus now require an ActorSystem
==============================================================================

Both the ``EventStream`` (:ref:`Scala <event-stream-scala>`, :ref:`Java <event-stream-java>`) and the
``ManagedActorClassification``, ``ManagedActorEventBus`` (:ref:`Scala <actor-classification-scala>`, :ref:`Java <actor-classification-java>`) now
require an ``ActorSystem`` to properly operate. The reason for that is moving away from stateful internal lifecycle checks
to a fully reactive model for unsubscribing actors that have ``Terminated``. Therefore the ``ActorClassification``
and ``ActorEventBus`` was deprecated and replaced by ``ManagedActorClassification`` and ``ManagedActorEventBus`` 

If you have implemented a custom event bus, you will need to pass in the actor system through the constructor now:

.. includecode:: ../scala/code/docs/event/EventBusDocSpec.scala#actor-bus

If you have been creating EventStreams manually, you now have to provide an actor system and *start the unsubscriber*:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/event/EventStreamSpec.scala#event-bus-start-unsubscriber-scala

Please note that this change affects you only if you have implemented your own buses, Akka's own ``context.eventStream``
is still there and does not require any attention from you concerning this change.

FSM notifies on same state transitions
======================================
When changing states in an Finite-State-Machine Actor (``FSM``), state transition events are emitted and can be handled by the user
either by registering ``onTransition`` handlers or by subscribing to these events by sending it an ``SubscribeTransitionCallBack`` message.

Previously in ``2.3.x`` when an ``FSM`` was in state ``A`` and performed an ``goto(A)`` transition, no state transition notification would be sent.
This is because it would effectively stay in the same state, and was deemed to be semantically equivalent to calling ``stay()``.

In ``2.4.x`` when an ``FSM`` performs a any ``goto(X)`` transition, it will always trigger state transition events.
Which turns out to be useful in many systems where same-state transitions actually should have an effect.

In case you do *not* want to trigger a state transition event when effectively performing an ``X->X`` transition, use ``stay()`` instead.

Cluster Sharding Entry Path Change
==================================
Previously in ``2.3.x`` entries were direct children of the local ``ShardRegion``. In examples the ``persistenceId`` of entries
included ``self.path.parent.name`` to include the cluster type name.

In ``2.4.x`` entries are now children of a ``Shard``, which in turn is a child of the local ``ShardRegion``. To include the shard
type in the ``persistenceId`` it is now accessed by ``self.path.parent.parent.name`` from each entry.


Circuit Breaker Timeout Change
==============================
In ``2.3.x`` calls protected by the ``CircuitBreaker`` were allowed to run indefinitely and the check to see if the timeout had been exceeded was done after the call had returned.

In ``2.4.x`` the failureCount of the Breaker will be increased as soon as the timeout is reached and a ``Failure[TimeoutException]`` will be returned immediately for asynchronous calls. Synchronous calls will now throw a ``TimeoutException`` after the call is finished.


Removed Deprecated Features
===========================

The following, previously deprecated, features have been removed:

* akka-dataflow

* akka-transactor

* durable mailboxes (akka-mailboxes-common, akka-file-mailbox)

* Cluster.publishCurrentClusterState

* akka.cluster.auto-down, replaced by akka.cluster.auto-down-unreachable-after in Akka 2.3

* Old routers and configuration.

  Note that in router configuration you must now specify if it is a ``pool`` or a ``group``
  in the way that was introduced in Akka 2.3.

* Timeout constructor without unit

* JavaLoggingEventHandler, replaced by JavaLogger

* UntypedActorFactory

* Java API TestKit.dilated, moved to JavaTestKit.dilated

Slf4j logging filter
====================

If you use ``Slf4jLogger`` you should add the following configuration::

    akka.logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"

It will filter the log events using the backend configuration (e.g. logback.xml) before
they are published to the event bus.

Pool routers nrOfInstances method now takes ActorSystem
=======================================================

In order to make cluster routers smarter about when they can start local routees,
``nrOfInstances`` defined on ``Pool`` now takes ``ActorSystem`` as an argument.
In case you have implemented a custom Pool you will have to update the method's signature,
however the implementation can remain the same if you don't need to rely on an ActorSystem in your logic.

Logger names use full class name 
================================
Previously, few places in akka used "simple" logger names, such as ``Cluster`` or ``Remoting``.
Now they use full class names, such as ``akka.cluster.Cluster`` or ``akka.remote.Remoting``,
in order to allow package level log level definitions and ease source code lookup. 
In case you used specific "simple" logger name based rules in your ``logback.xml`` configurations,
please change them to reflect appropriate package name, such as
``<logger name='akka.cluster' level='warn' />`` or ``<logger name='akka.remote' level='error' />``

Default interval for TestKit.awaitAssert changed to 100 ms
==========================================================

Default check interval changed from 800 ms to 100 ms. You can define the interval explicitly if you need a
longer interval.

Akka Persistence
================

Mendatory persistenceId
-----------------------

It is now mandatory to define the ``persistenceId`` in subclasses of ``PersistentActor``, ``UntypedPersistentActor``
and ``AbstractPersistentId``.

The rationale behind this change being stricter de-coupling of your Actor hierarchy and the logical
"which persistent entity this actor represents".

In case you want to preserve the old behavior of providing the actor's path as the default ``persistenceId``, you can easily
implement it yourself either as a helper trait or simply by overriding ``persistenceId`` as follows::

    override def persistenceId = self.path.toStringWithoutAddress

Secure Cookies
==============

`Secure cookies` feature was deprecated.

New Cluster Metrics Extension 
=============================
Previously, cluster metrics functionality was located in the ``akka-cluster`` jar.
Now it is split out and moved into a separate akka module: ``akka-cluster-metrics`` jar.
The module comes with few enhancements, such as use of Kamon sigar-loader 
for native library provisioning as well as use of statistical averaging of metrics data.
Note that both old and new metrics configuration entries in the ``reference.conf`` 
are still in the same name space ``akka.cluster.metrics`` but are not compatible.
Make sure to disable legacy metrics in akka-cluster: ``akka.cluster.metrics.enabled=off``,
since it is still enabled in akka-cluster by default (for compatibility with past releases).
Router configuration entries have also changed for the module, they use prefix ``cluster-metrics-``:
``cluster-metrics-adaptive-pool`` and ``cluster-metrics-adaptive-group``
Metrics extension classes and objects are located in the new package ``akka.cluster.metrics``. 
Please see :ref:`Scala <cluster_metrics_scala>`, :ref:`Java <cluster_metrics_java>` for more information.

Microkernel is Deprecated
=========================

Akka Microkernel is deprecated and will be removed. It is replaced by using an ordinary
user defined main class and packaging with `sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_
or `Typesafe ConductR <http://typesafe.com/products/conductr>`_.
Please see :ref:`deployment-scenarios` for more information.

Cluster tools moved to separate module
======================================

The Cluster Singleton, Distributed Pub-Sub, and Cluster Client previously located in the ``akka-contrib``
jar is now moved to a separate module named ``akka-cluster-tools``. You need to replace this dependency
if you use any of these tools.

The classes changed package name from ``akka.contrib.pattern`` to ``akka.cluster.singleton``, ``akka.cluster.pubsub``
and ``akka.cluster.client``.

The configuration properties changed name to ``akka.cluster.pub-sub`` and ``akka.cluster.client``.

Cluster sharding moved to separate module
=========================================

The Cluster Sharding previously located in the ``akka-contrib`` jar is now moved to a separate module
named ``akka-cluster-sharding``. You need to replace this dependency if you use Cluster Sharding.

The classes changed package name from ``akka.contrib.pattern`` to ``akka.cluster.sharding``.

The configuration properties changed name to ``akka.cluster.sharding``.

ClusterSharding construction
============================

Several parameters of the ``start`` method of the ``ClusterSharding`` extension are now defined
in a settings object ``ClusterShardingSettings``.
It can be created from system configuration properties and also amended with API.
These settings can be defined differently per entry type if needed.

Starting the ``ShardRegion`` in proxy mode is now done with the ``startProxy`` method 
of the ``ClusterSharding`` extension instead of the optional ``entryProps`` parameter.

Entry was renamed to Entity, for example in the ``MessagesExtractor`` in the Java API
and the ``EntityId`` type in the Scala API.

``idExtractor`` function was renamed to ``extractEntityId``. ``shardResolver`` function 
was renamed to ``extractShardId``.

ClusterSingletonManager and ClusterSingletonProxy construction
==============================================================

Parameters to the ``Props`` factory methods have been moved to settings object ``ClusterSingletonManagerSettings``
and ``ClusterSingletonProxySettings``. These can be created from system configuration properties and also
amended with API as needed.

DistributedPubSub construction
==============================

Normally, the ``DistributedPubSubMediator`` actor is started by the ``DistributedPubSubExtension``.
This extension has been renamed to ``DistributedPubSub``. It is also possible to start
it as an ordinary actor if you need multiple instances of it with different settings.
The parameters of the ``Props`` factory methods in the ``DistributedPubSubMediator`` companion
has been moved to settings object ``DistributedPubSubSettings``. This can be created from
system configuration properties and also amended with API as needed.

ClusterClient construction
==========================

The parameters of the ``Props`` factory methods in the ``ClusterClient`` companion
has been moved to settings object ``ClusterClientSettings``. This can be created from
system configuration properties and also amended with API as needed.

Normally, the ``ClusterReceptionist`` actor is started by the ``ClusterReceptionistExtension``.
This extension has been renamed to ``ClusterClientReceptionist``. It is also possible to start
it as an ordinary actor if you need multiple instances of it with different settings.
The parameters of the ``Props`` factory methods in the ``ClusterReceptionist`` companion
has been moved to settings object ``ClusterReceptionistSettings``. This can be created from
system configuration properties and also amended with API as needed.

Asynchronous ShardAllocationStrategy
====================================

The methods of the ``ShardAllocationStrategy`` and ``AbstractShardAllocationStrategy`` in Cluster Sharding
have changed return type to a ``Future`` to support asynchronous decision. For example you can ask an
actor external actor of how to allocate shards or rebalance shards.

For the synchronous case you can return the result via ``scala.concurrent.Future.successful`` in Scala or 
``akka.dispatch.Futures.successful`` in Java.
