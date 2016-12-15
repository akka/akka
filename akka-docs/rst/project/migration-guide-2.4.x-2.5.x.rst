.. _migration-guide-2.4.x-2.5.x:

##############################
Migration Guide 2.4.x to 2.5.x
##############################

Akka Persistence
================

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

Akka Streams
============

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


Actor DSL
=========

Actor DSL deprecation
---------------------

Actor DSL is a rarely used feature and thus will be deprecated and removed.
Use plain ``system.actorOf`` instead of the DSL to create Actors if you have been using it.

Akka Persistence
================

Persistence Plugin Proxy
------------------------

A new :ref:`persistence plugin proxy<persistence-plugin-proxy>` was added, that allows sharing of an otherwise
non-sharable journal or snapshot store. The proxy is available by setting ``akka.persistence.journal.plugin`` or
``akka.persistence.snapshot-store.plugin`` to ``akka.persistence.journal.proxy`` or ``akka.persistence.snapshot-store.proxy``,
respectively. The proxy supplants the :ref:`Shared LevelDB journal<shared-leveldb-journal>`.


Cluster
=======

Cluster Management Command Line Tool
------------------------------------

There is a new cluster management tool with HTTP API that has the same functionality as the command line tool.
The HTTP API gives you access to cluster membership information as JSON including full reachability status between the nodes.
It supports the ordinary cluster operations such as join, leave, and down.

See documentation of `akka/akka-cluster-management <https://github.com/akka/akka-cluster-management>`_.

The command line script for cluster management has been deprecated and is scheduled for removal 
in the next major version. Use the HTTP API with `curl <https://curl.haxx.se/>`_ or similar instead.
