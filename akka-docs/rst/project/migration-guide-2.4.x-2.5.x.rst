.. _migration-guide-2.4.x-2.5.x:

##############################
Migration Guide 2.4.x to 2.5.x
##############################

Actor
=====

Actor DSL deprecation
---------------------

Actor DSL is a rarely used feature and thus will be deprecated and removed.
Use plain ``system.actorOf`` instead of the DSL to create Actors if you have been using it.

Streams
=======

Removal of StatefulStage, PushPullStage
---------------------------------------

``StatefulStage`` and ``PushPullStage`` were first introduced in Akka Streams 1.0, and later deprecated 
and replaced by ``GraphStage`` in 2.0-M2. The ``GraphStage`` API has all features (and even more) as the 
previous APIs and is even nicer to use.

Please refer to the GraphStage documentation :ref:` for Scala <graphstage-scala>` or
the documentation :ref:`for Java <graphstage-scala>`, for details on building custom GraphStages.

``StatefulStage`` would be migrated to a simple ``GraphStage`` that contains some mutable state in its ``GraphStageLogic``,
and ``PushPullStage`` directly translate to graph stages.

Removal of ``Source.transform``, replaced by ``via``
----------------------------------------------------

Along with the removal of ``Stage`` (as described above), the ``transform`` methods creating Flows/Sources/Sinks
from ``Stage`` have been removed. They are replaced by using ``GraphStage`` instances with ``via``, e.g.::

   exampleFlow.transform(() => new MyStage())
   
would now be::

   myFlow.via(new MyGraphStage)

as the ``GraphStage`` itself is a factory of logic instances.

Deprecation of ActorSubscriber and ActorPublisher
-------------------------------------------------

The classes ``ActorPublisher`` and ``ActorSubscriber`` were the first user-facing Reactive Streams integration
API that we provided for end-users. Akka Streams APIs have evolved and improved a lot since then, and now
there is no need to use these low-level abstractions anymore. It is easy to get things wrong when implementing them,
and one would have to validate each implementation of such Actor using the Reactive Streams Technology Compatibility Kit.

The replacement API is the powerful ``GraphStage``. It has all features that raw Actors provided for implementing Stream 
stages and adds additional protocol and type-safety. You can learn all about it in the documentation: 
:ref:`stream-customize-scala`and :ref:`Custom stream processing in JavaDSL <stream-customize-java>`. 

You should also read the blog post series on the official team blog, starting with `Mastering GraphStages, part I`_,
which explains using and implementing GraphStages in more practical terms than the reference documentation.

.. _Mastering GraphStages, part I: http://blog.akka.io/streams/2016/07/30/mastering-graph-stage-part-1

Remote
======

Mutual TLS authentication now required by default for netty-based SSL transport
-------------------------------------------------------------------------------

Mutual TLS authentication is now required by default for the netty-based SSL transport.

Nodes that are configured with this setting to ``on`` might not be able to receive messages from nodes that run on older
versions of akka-remote. This is because in versions of Akka < 2.4.12 the active side of the remoting
connection will not send over certificates even if asked to.

It is still possible to make a rolling upgrade from a version < 2.4.12 by doing the upgrade stepwise:
 * first, upgrade Akka to the latest version but keep ``akka.remote.netty.ssl.require-mutual-authentication`` at ``off``
   and do a first rolling upgrade
 * second, turn the setting to ``on`` and do another rolling upgrade

For more information see the documentation for the ``akka.remote.netty.ssl.require-mutual-authentication` configuration setting
in akka-remote's `reference.conf`_.

.. _reference.conf: https://github.com/akka/akka/blob/master/akka-remote/src/main/resources/reference.conf

Cluster
=======

Coordinated Shutdown
--------------------

There is a new extension named ``CoordinatedShutdown`` that will stop certain actors and 
services in a specific order and perform registered tasks during the shutdown process.

When using Akka Cluster, tasks for graceful leaving of cluster including graceful 
shutdown of Cluster Singletons and Cluster Sharding are now performed automatically.

Previously it was documented that things like terminating the ``ActorSystem`` should be
done when the cluster member was removed, but this was very difficult to get right.
That is now taken care of automatically. This might result in changed behavior, hopefully
to the better. It might also be in conflict with your previous shutdown code so please 
read the documentation for the Coordinated Shutdown and revisit your own implementations. 
Most likely your implementation will not be needed any more or it can be simplified.

More information can be found in the :ref:`documentation for Scala <coordinated-shutdown-scala>` or
:ref:`documentation for Java <coordinated-shutdown-lambda>`

For some tests it might be undesired to terminate the ``ActorSystem`` via ``CoordinatedShutdown``.
You can disable that by adding the following to the configuration of the ``ActorSystem`` that is 
used in the test::

  # Don't terminate ActorSystem via CoordinatedShutdown in tests
  akka.coordinated-shutdown.terminate-actor-system = off
  akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
  akka.cluster.run-coordinated-shutdown-when-down = off 

Cluster Management Command Line Tool
------------------------------------

There is a new cluster management tool with HTTP API that has the same functionality as the command line tool.
The HTTP API gives you access to cluster membership information as JSON including full reachability status between the nodes.
It supports the ordinary cluster operations such as join, leave, and down.

See documentation of `akka/akka-cluster-management <https://github.com/akka/akka-cluster-management>`_.

The command line script for cluster management has been deprecated and is scheduled for removal
in the next major version. Use the HTTP API with `curl <https://curl.haxx.se/>`_ or similar instead.

Persistence
===========

Removal of PersistentView
-------------------------

After being deprecated for a long time, and replaced by :ref:`Persistence Query Java <persistence-query-java>`
(:ref:`Persistence Query Scala <persistence-query-scala>`) ``PersistentView`` has been removed now removed.

The corresponding query type is ``EventsByPersistenceId``. There are several alternatives for connecting the ``Source``
to an actor corresponding to a previous ``PersistentView``. There are several alternatives for connecting the ``Source``
to an actor corresponding to a previous ``PersistentView`` actor which are documented in :ref:`stream-integrations-scala`
for Scala and :ref:`Java <stream-integrations-java>`.

The consuming actor may be a plain ``Actor`` or an ``PersistentActor`` if it needs to store its own state (e.g. ``fromSequenceNr`` offset).

Please note that Persistence Query is not experimental anymore in Akka ``2.5.0``, so you can safely upgrade to it.

Persistence Plugin Proxy
------------------------

A new :ref:`persistence plugin proxy<persistence-plugin-proxy>` was added, that allows sharing of an otherwise
non-sharable journal or snapshot store. The proxy is available by setting ``akka.persistence.journal.plugin`` or
``akka.persistence.snapshot-store.plugin`` to ``akka.persistence.journal.proxy`` or ``akka.persistence.snapshot-store.proxy``,
respectively. The proxy supplants the :ref:`Shared LevelDB journal<shared-leveldb-journal>`.

Persistence Query
=================

Persistence Query has been promoted to a stable module.
Only slight API changes were made since the module was introduced:

Query naming consistency improved
---------------------------------
Queries always fall into one of the two categories: infinite or finite ("current").
The naming convention for these categories of queries was solidified and is now as follows:

- "infinite" - e.g. ``eventsByTag``, ``persistenceIds`` - which will keep emitting events as they are persisted and match the query.
- "finite", also known as "current" - e.g. ``currentEventsByTag``, ``currentPersistenceIds`` - which will complete the stream once the query completed,
  for the journal's definition of "current". For example in an SQL store it would mean it only queries the database once.

Only the ``AllPersistenceIdsQuery`` class and method name changed due to this.
The class is now called ``PersistenceIdsQuery``, and the method which used to be ``allPersistenceIds`` is now ``persistenceIds``.

Queries now use ``Offset`` instead of ``Long`` for offsets
----------------------------------------------------------

This change was made to better accomodate the various types of Journals and their understanding what an offset is.
For example, in some journals an offset is always a time, while in others it is a numeric offset (like a sequence id).

Instead of the previous ``Long`` offset you can now use the provided ``Offset`` factories (and types):

- ``akka.persistence.query.Offset.sequence(value: Long)``,
- ``akka.persistence.query.Offset.timeBasedUUID(value: UUID)``
- and finally ``NoOffset`` if not offset should be used.

Journals are also free to provide their own specific ``Offset`` types. Consult your journal plugin's documentation for details.

Agents
======

Agents are now deprecated
-------------------------

Akka Agents are a very simple way of containing mutable state and allowing to access it safely from
multiple threads. The abstraction is leaky though, as Agents do not work over the network (unlike Akka Actors).

As users were often confused by "when to use an Actor vs. when to use an Agent?" a decision was made to deprecate
the Agents, as they rarely are really enough and do not fit the Akka spirit of thinking about distribution.
We also anticipate to replace the uses of Agents by the upcoming Akka Typed, so in preparation thereof the Agents have been deprecated in 2.5.

If you use Agents and would like to take over the maintanance thereof, please contact the team on gitter or github.

Distributed Data
================

Map allow generic type for the keys
-----------------------------------

In 2.4 the key of any Distributed Data map always needed to be of type String. In 2.5 you can use any type for the key. This means that
every map (ORMap, LWWMap, PNCounterMap, ORMultiMap) now takes an extra type parameter to specify the key type. To migrate
existing code from 2.4 to 2.5 you simple add String as key type, for example: `ORMultiMap[Foo]` becomes `ORMultiMap[String, Foo]`.
`PNCounterMap` didn't take a type parameter in version 2.4, so `PNCounterMap` in 2.4 becomes `PNCounterMap[String]` in 2.5.
Java developers should use `<>` instead of `[]`, e.g: `PNCounterMap<String>`.

**NOTE: Even though the interface is not compatible between 2.4 and 2.5, the binary protocol over the wire is (as long
as you use String as key type). This means that 2.4 nodes can synchronize with 2.5 nodes.**

Subscribers
-----------

When an entity is removed subscribers will not receive ``Replicator.DataDeleted`` any more.
They will receive ``Replicator.Deleted`` instead.
