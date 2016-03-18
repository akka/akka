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
the :ref:`typed-scala` project that is currently being developed in open preview mode. If you are using TypedActors
in your projects you are advised to look into this, as it is superior to the Active Object pattern expressed
in TypedActors. The generic ActorRefs in Akka Typed allow the same type-safety that is afforded by
TypedActors while retaining all the other benefits of an explicit actor model (including the ability to
change behaviors etc.).

It is likely that TypedActors will be officially deprecated in the next major update of Akka and subsequently removed.

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

Protobuf Dependency
===================

The transitive dependency to Protobuf has been removed to make it possible to use any version
of Protobuf for the application messages. If you use Protobuf in your application you need
to add the following dependency with desired version number::

    "com.google.protobuf" % "protobuf-java" % "2.5.0" 

Internally Akka is using an embedded version of protobuf that corresponds to ``com.google.protobuf/protobuf-java``
version 2.5.0. The package name of the embedded classes has been changed to ``akka.protobuf``.

Added parameter validation to RootActorPath
===========================================
Previously ``akka.actor.RootActorPath`` allowed passing in arbitrary strings into its name parameter,
which is meant to be the *name* of the root Actor. Subsequently, if constructed with an invalid name
such as a full path for example (``/user/Full/Path``) some features using this path may transparently fail -
such as using ``actorSelection`` on such invalid path.

In Akka 2.4.x the ``RootActorPath`` validates the input and may throw an ``IllegalArgumentException`` if
the passed in name string is illegal (contains ``/`` elsewhere than in the begining of the string or contains ``#``).

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


Circuit Breaker Timeout Change
==============================
In ``2.3.x`` calls protected by the ``CircuitBreaker`` were allowed to run indefinitely and the check to see if the timeout had been exceeded was done after the call had returned.

In ``2.4.x`` the failureCount of the Breaker will be increased as soon as the timeout is reached and a ``Failure[TimeoutException]`` will be returned immediately for asynchronous calls. Synchronous calls will now throw a ``TimeoutException`` after the call is finished.


Slf4j logging filter
====================

If you use ``Slf4jLogger`` you should add the following configuration::

    akka.logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"

It will filter the log events using the backend configuration (e.g. logback.xml) before
they are published to the event bus.

Inbox.receive Java API
======================

``Inbox.receive`` now throws a checked ``java.util.concurrent.TimeoutException`` exception if the receive timeout
is reached.


Pool routers nrOfInstances method now takes ActorSystem
=======================================================

In order to make cluster routers smarter about when they can start local routees,
``nrOfInstances`` defined on ``Pool`` now takes ``ActorSystem`` as an argument.
In case you have implemented a custom Pool you will have to update the method's signature,
however the implementation can remain the same if you don't need to rely on an ActorSystem in your logic.

Group routers paths method now takes ActorSystem
================================================

In order to make cluster routers smarter about when they can start local routees,
``paths`` defined on ``Group`` now takes ``ActorSystem`` as an argument.
In case you have implemented a custom Group you will have to update the method's signature,
however the implementation can remain the same if you don't need to rely on an ActorSystem in your logic.

Cluster aware router max-total-nr-of-instances
==============================================

In 2.3.x the deployment configuration property ``nr-of-instances`` was used for
cluster aware routers to specify total number of routees in the cluster.
This was confusing, especially since the default value is 1.

In 2.4.x there is a new deployement property ``cluster.max-total-nr-of-instances`` that 
defines total number of routees in the cluster. By default ``max-total-nr-of-instances`` 
is set to a high value (10000) that will result in new routees added to the router when nodes join the cluster.
Set it to a lower value if you want to limit total number of routees.

For backwards compatibility reasons ``nr-of-instances`` is still used if defined by user,
i.e. if defined it takes precedence over ``max-total-nr-of-instances``.

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

Secure Cookies
==============

`Secure cookies` feature was deprecated.

AES128CounterInetRNG and AES256CounterInetRNG are Deprecated
============================================================

Use ``AES128CounterSecureRNG`` or ``AES256CounterSecureRNG`` as 
``akka.remote.netty.ssl.security.random-number-generator``. 

Microkernel is Deprecated
=========================

Akka Microkernel is deprecated and will be removed. It is replaced by using an ordinary
user defined main class and packaging with `sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_
or `Lightbend ConductR <http://www.lightbend.com/products/conductr>`_.
Please see :ref:`deployment-scenarios` for more information.

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

Cluster Sharding Entry Path Change
==================================
Previously in ``2.3.x`` entries were direct children of the local ``ShardRegion``. In examples the ``persistenceId`` of entries
included ``self.path.parent.name`` to include the cluster type name.

In ``2.4.x`` entries are now children of a ``Shard``, which in turn is a child of the local ``ShardRegion``. To include the shard
type in the ``persistenceId`` it is now accessed by ``self.path.parent.parent.name`` from each entry.

Asynchronous ShardAllocationStrategy
====================================

The methods of the ``ShardAllocationStrategy`` and ``AbstractShardAllocationStrategy`` in Cluster Sharding
have changed return type to a ``Future`` to support asynchronous decision. For example you can ask an
actor external actor of how to allocate shards or rebalance shards.

For the synchronous case you can return the result via ``scala.concurrent.Future.successful`` in Scala or 
``akka.dispatch.Futures.successful`` in Java.

Cluster Sharding internal data
==============================

The Cluster Sharding coordinator stores the locations of the shards using Akka Persistence.
This data can safely be removed when restarting the whole Akka Cluster.

The serialization format of the internal persistent events stored by the Cluster Sharding coordinator
has been changed and it cannot load old data from 2.3.x or some 2.4 milestone.

The ``persistenceId`` of the Cluster Sharding coordinator has been changed since 2.3.x so
it should not load such old data, but it can be a problem if you have used a 2.4
milestone release. In that case you should remove the persistent data that the 
Cluster Sharding coordinator stored. Note that this is not application data.

You can use the :ref:`RemoveInternalClusterShardingData <RemoveInternalClusterShardingData-scala>`
utility program to remove this data.

The new ``persistenceId`` is ``s"/sharding/${typeName}Coordinator"``.
The old ``persistenceId`` is ``s"/user/sharding/${typeName}Coordinator/singleton/coordinator"``.  

ClusterSingletonManager and ClusterSingletonProxy construction
==============================================================

Parameters to the ``Props`` factory methods have been moved to settings object ``ClusterSingletonManagerSettings``
and ``ClusterSingletonProxySettings``. These can be created from system configuration properties and also
amended with API as needed.

The buffer size of the ``ClusterSingletonProxy`` can be defined in the ``ClusterSingletonProxySettings``
instead of defining ``stash-capacity`` of the mailbox. Buffering can be disabled by using a 
buffer size of 0.

The ``singletonPath`` parameter of ``ClusterSingletonProxy.props`` has changed. It is now named 
``singletonManagerPath`` and is the logical path of the singleton manager, e.g. ``/user/singletonManager``,
which ends with the name you defined in ``actorOf`` when creating the ``ClusterSingletonManager``.
In 2.3.x it was the path to singleton instance, which was error-prone because one had to provide both
the name of the singleton manager and the singleton actor.

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

The buffer size of the ``ClusterClient`` can be defined in the ``ClusterClientSettings``
instead of defining ``stash-capacity`` of the mailbox. Buffering can be disabled by using a 
buffer size of 0.

Normally, the ``ClusterReceptionist`` actor is started by the ``ClusterReceptionistExtension``.
This extension has been renamed to ``ClusterClientReceptionist``. It is also possible to start
it as an ordinary actor if you need multiple instances of it with different settings.
The parameters of the ``Props`` factory methods in the ``ClusterReceptionist`` companion
has been moved to settings object ``ClusterReceptionistSettings``. This can be created from
system configuration properties and also amended with API as needed.

The ``ClusterReceptionist`` actor that is started by the ``ClusterReceptionistExtension``
is now started as a ``system`` actor instead of a ``user`` actor, i.e. the default path for
the ``ClusterClient`` initial contacts has changed to
``"akka.tcp://system@hostname:port/system/receptionist"``.  

ClusterClient sender
====================

In 2.3 the ``sender()`` of the response messages, as seen by the client, was the 
actor in cluster.

In 2.4 the ``sender()`` of the response messages, as seen by the client, is ``deadLetters``
since the client should normally send subsequent messages via the ``ClusterClient``.
It is possible to pass the original sender inside the reply messages if
the client is supposed to communicate directly to the actor in the cluster.

Akka Persistence
================

Experimental removed
--------------------

The artifact name has changed from ``akka-persistence-experimental`` to ``akka-persistence``.

New sbt dependency::

  "com.typesafe.akka" %% "akka-persistence" % "@version@" @crossString@

New Maven dependency::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-persistence_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

The artefact name of the Persistent TCK has changed from ``akka-persistence-tck-experimental`` (``akka-persistence-experimental-tck``) to
``akka-persistence-tck``.

Mandatory persistenceId
-----------------------

It is now mandatory to define the ``persistenceId`` in subclasses of ``PersistentActor``, ``UntypedPersistentActor``
and ``AbstractPersistentId``.

The rationale behind this change being stricter de-coupling of your Actor hierarchy and the logical
"which persistent entity this actor represents".

In case you want to preserve the old behavior of providing the actor's path as the default ``persistenceId``, you can easily
implement it yourself either as a helper trait or simply by overriding ``persistenceId`` as follows::

    override def persistenceId = self.path.toStringWithoutAddress

Failures
--------

Backend journal failures during recovery and persist are treated differently than in 2.3.x. The ``PersistenceFailure``
message is removed and the actor is unconditionally stopped. The new behavior and reasons for it is explained in
:ref:`failures-scala`. 

Persist sequence of events
--------------------------

The ``persist`` method that takes a ``Seq`` (Scala) or ``Iterable`` (Java) of events parameter was deprecated and
renamed to ``persistAll`` to avoid mistakes of persisting other collection types as one single event by calling
the overloaded ``persist(event)`` method.

non-permanent deletion
----------------------

The ``permanent`` flag in ``deleteMessages`` was removed. non-permanent deletes are not supported
any more. Events that were deleted with ``permanent=false`` with older version will
still not be replayed in this version.

Recover message is gone, replaced by Recovery config
----------------------------------------------------
Previously the way to cause recover in PersistentActors was sending them a ``Recover()`` message.
Most of the time it was the actor itself sending such message to ``self`` in its ``preStart`` method,
however it was possible to send this message from an external source to any ``PersistentActor`` or ``PresistentView``
to make it start recovering.

This style of starting recovery does not fit well with usual Actor best practices: an Actor should be independent
and know about its internal state, and also about its recovery or lack thereof. In order to guide users towards
more independent Actors, the ``Recovery()`` object is now not used as a message, but as configuration option
used by the Actor when it starts. In order to migrate previous code which customised its recovery mode use this example
as reference::

    // previously
    class OldCookieMonster extends PersistentActor {
      def preStart() = self ! Recover(toSequenceNr = 42L)
      // ...
    }
    // now:
    class NewCookieMonster extends PersistentActor {
      override def recovery = Recovery(toSequenceNr = 42L)
      // ...
    }

Sender reference of replayed events is deadLetters
--------------------------------------------------
While undocumented, previously the ``sender()`` of the replayed messages would be the same sender that originally had
sent the message. Since sender is an ``ActorRef`` and those events are often replayed in different incarnations of
actor systems and during the entire lifetime of the app, relying on the existence of this reference is most likely
not going to succeed. In order to avoid bugs in the style of "it worked last week", the ``sender()`` reference is now not
stored, in order to avoid potential bugs which this could have provoked.

The previous behaviour was never documented explicitly (nor was it a design goal), so it is unlikely that applications
have explicitly relied on this behaviour, however if you find yourself with an application that did exploit this you
should rewrite it to explicitly store the ``ActorPath`` of where such replies during replay may have to be sent to,
instead of relying on the sender reference during replay.

max-message-batch-size config
-----------------------------

Configuration property ``akka.persistence.journal.max-message-batch-size`` has been moved into the plugin configuration
section, to allow different values for different journal plugins. See ``reference.conf``.

akka.persistence.snapshot-store.plugin config
---------------------------------------------

The configuration property ``akka.persistence.snapshot-store.plugin`` now by default is empty. To restore the previous
setting add ``akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"`` to your application.conf.
See ``reference.conf``.

PersistentView is deprecated
----------------------------

``PersistentView`` is deprecated. Use :ref:`persistence-query-scala` instead. The corresponding
query type is ``EventsByPersistenceId``. There are several alternatives for connecting the ``Source``
to an actor corresponding to a previous ``PersistentView`` actor:

* `Sink.actorRef`_ is simple, but has the disadvantage that there is no back-pressure signal from the 
  destination actor, i.e. if the actor is not consuming the messages fast enough the mailbox of the actor will grow
* `mapAsync`_ combined with :ref:`actors-ask-lambda` is almost as simple with the advantage of back-pressure
  being propagated all the way
* `ActorSubscriber`_ in case you need more fine grained control
  
The consuming actor may be a plain ``Actor`` or a ``PersistentActor`` if it needs to store its
own state (e.g. fromSequenceNr offset).

.. _Sink.actorRef: http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/scala/stream-integrations.html#Sink_actorRef
.. _mapAsync: http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/stages-overview.html#Asynchronous_processing_stages
.. _ActorSubscriber: http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/scala/stream-integrations.html#ActorSubscriber

Persistence Plugin APIs
=======================

SyncWriteJournal removed
------------------------

``SyncWriteJournal`` removed in favor of using ``AsyncWriteJournal``. 

If the storage backend API only supports synchronous, blocking writes, 
the methods can still be implemented in terms of the asynchronous API.
Example of how to do that is in included in the 
See :ref:`Journal plugin API for Scala <journal-plugin-api>`
or :ref:`Journal plugin API for Java <journal-plugin-api-java>`.

SnapshotStore: Snapshots can now be deleted asynchronously (and report failures)
--------------------------------------------------------------------------------
Previously the ``SnapshotStore`` plugin SPI did not allow for asynchronous deletion of snapshots,
and failures of deleting a snapshot may have been even silently ignored.

Now ``SnapshotStore`` must return a ``Future`` representing the deletion of the snapshot.
If this future completes successfully the ``PersistentActor`` which initiated the snapshotting will
be notified via an ``DeleteSnapshotSuccess`` message. If the deletion fails for some reason a ``DeleteSnapshotFailure``
will be sent to the actor instead.

For ``criteria`` based deletion of snapshots (``def deleteSnapshots(criteria: SnapshotSelectionCriteria)``) equivalent
``DeleteSnapshotsSuccess`` and ``DeleteSnapshotsFailure`` messages are sent, which contain the specified criteria,
instead of ``SnapshotMetadata`` as is the case with the single snapshot deletion messages.

SnapshotStore: Removed 'saved' callback
---------------------------------------
Snapshot Stores previously were required to implement a ``def saved(meta: SnapshotMetadata): Unit`` method which
would be called upon successful completion of a ``saveAsync`` (``doSaveAsync`` in Java API) snapshot write.

Currently all journals and snapshot stores perform asynchronous writes and deletes, thus all could potentially benefit
from such callback methods. The only gain these callback give over composing an ``onComplete`` over ``Future`` returned
by the journal or snapshot store is that it is executed in the Actors context, thus it can safely (without additional
synchronization modify its internal state - for example a "pending writes" counter).

However, this feature was not used by many plugins, and expanding the API to accomodate all callbacks would have grown
the API a lot. Instead, Akka Persistence 2.4.x introduces an additional (optionally overrideable)
``receivePluginInternal:Actor.Receive`` method in the plugin API, which can be used for handling those as well as any custom messages
that are sent to the plugin Actor (imagine use cases like "wake up and continue reading" or custom protocols which your
specialised journal can implement).

Implementations using the previous feature should adjust their code as follows::

    // previously
    class MySnapshots extends SnapshotStore {
      // old API:
      // def saved(meta: SnapshotMetadata): Unit = doThings()

      // new API:
      def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
         // completion or failure of the returned future triggers internal messages in receivePluginInternal
         val f: Future[Unit] = ???

         // custom messages can be piped to self in order to be received in receivePluginInternal
         f.map(MyCustomMessage(_)) pipeTo self

         f
      }

      def receivePluginInternal = {
        case SaveSnapshotSuccess(metadata) => doThings()
        case MyCustomMessage(data)         => doOtherThings()
      }

      // ...
    }

SnapshotStore: Java 8 Optional used in Java plugin APIs
-------------------------------------------------------
In places where previously ``akka.japi.Option`` was used in Java APIs, including the return type of ``doLoadAsync``,
the Java 8 provided ``Optional`` type is used now.

Please remember that when creating an ``java.util.Optional`` instance from a (possibly) ``null`` value you will want to
use the non-throwing ``Optional.fromNullable`` method, which converts a ``null`` into a ``None`` value - which is
slightly different than its Scala counterpart (where ``Option.apply(null)`` returns ``None``).

Atomic writes
-------------

``asyncWriteMessages`` takes a ``immutable.Seq[AtomicWrite]`` parameter instead of
``immutable.Seq[PersistentRepr]``. 

Each `AtomicWrite` message contains the single ``PersistentRepr`` that corresponds to the event that was 
passed to the ``persist`` method of the ``PersistentActor``, or it contains several ``PersistentRepr`` 
that corresponds to the events that were passed to the ``persistAll`` method of the ``PersistentActor``.
All ``PersistentRepr`` of the `AtomicWrite` must be written to the data store atomically, i.e. all or 
none must be stored.

If the journal (data store) cannot support atomic writes of multiple events it should
reject such writes with a ``Try`` ``Failure`` with an ``UnsupportedOperationException``
describing the issue. This limitation should also be documented by the journal plugin.

Rejecting writes
----------------

``asyncWriteMessages`` returns a ``Future[immutable.Seq[Try[Unit]]]``. 

The journal can signal that it rejects individual messages (``AtomicWrite``) by the returned 
`immutable.Seq[Try[Unit]]`. The returned ``Seq`` must have as many elements as the input 
``messages`` ``Seq``. Each ``Try`` element signals if the corresponding ``AtomicWrite``
is rejected or not, with an exception describing the problem. Rejecting a message means it
was not stored, i.e. it must not be included in a later replay. Rejecting a message is
typically done before attempting to store it, e.g. because of serialization error.

Read the :ref:`API documentation <journal-plugin-api>` of this method for more
information about the semantics of rejections and failures.

asyncReplayMessages Java API
----------------------------

The signature of `asyncReplayMessages` in the Java API changed from ``akka.japi.Procedure``
to ``java.util.function.Consumer``.

asyncDeleteMessagesTo
---------------------

The ``permanent`` deletion flag was removed. Support for non-permanent deletions was
removed. Events that were deleted with ``permanent=false`` with older version will
still not be replayed in this version.

References to "replay" in names
-------------------------------
Previously a number of classes and methods used the word "replay" interchangeably with the word "recover".
This lead to slight inconsistencies in APIs, where a method would be called ``recovery``, yet the
signal for a completed recovery was named ``ReplayMessagesSuccess``.

This is now fixed, and all methods use the same "recovery" wording consistently across the entire API.
The old ``ReplayMessagesSuccess`` is now called ``RecoverySuccess``, and an additional method called ``onRecoveryFailure``
has been introduced.

AtLeastOnceDelivery deliver signature
-------------------------------------
The signature of ``deliver`` changed slightly in order to allow both ``ActorSelection`` and ``ActorPath`` to be
used with it.

Previously:

    def deliver(destination: ActorPath, deliveryIdToMessage: Long ⇒ Any): Unit

Now:

    def deliver(destination: ActorSelection)(deliveryIdToMessage: Long ⇒ Any): Unit
    def deliver(destination: ActorPath)(deliveryIdToMessage: Long ⇒ Any): Unit

The Java API remains unchanged and has simply gained the 2nd overload which allows ``ActorSelection`` to be
passed in directly (without converting to ``ActorPath``).


Actor system shutdown
---------------------
``ActorSystem.shutdown``, ``ActorSystem.awaitTermination`` and ``ActorSystem.isTerminated`` has been
deprecated in favor of ``ActorSystem.terminate`` and ``ActorSystem.whenTerminated```. Both returns a
``Future[Terminated]`` value that will complete when the actor system has terminated.

To get the same behavior as ``ActorSystem.awaitTermination`` block and wait for ``Future[Terminated]`` value
with ``Await.result`` from the Scala standard library.

To trigger a termination and wait for it to complete:

    import scala.concurrent.duration._
    Await.result(system.terminate(), 10.seconds)

Be careful to not do any operations on the ``Future[Terminated]`` using the ``system.dispatcher``
as ``ExecutionContext`` as it will be shut down with the ``ActorSystem``, instead use for example
the Scala standard library context from ``scala.concurrent.ExecutionContext.global``.

::

    // import system.dispatcher <- this would not work
    import scala.concurrent.ExecutionContext.Implicits.global

    system.terminate().foreach { _ =>
      println("Actor system was shut down")
    }

