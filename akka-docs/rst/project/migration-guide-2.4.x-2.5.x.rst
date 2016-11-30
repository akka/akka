.. _migration-guide-2.4.x-2.5.x:

#######################################
Upcoming Migration Guide 2.4.x to 2.5.x
#######################################

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




Akka Persistence
================

Persistence Plugin Proxy
------------------------

A new :ref:`persistence plugin proxy<persistence-plugin-proxy>` was added, that allows sharing of an otherwise
non-sharable journal or snapshot store. The proxy is available by setting ``akka.persistence.journal.plugin`` or
``akka.persistence.snapshot-store.plugin`` to ``akka.persistence.journal.proxy`` or ``akka.persistence.snapshot-store.proxy``,
respectively. The proxy supplants the :ref:`Shared LevelDB journal<shared-leveldb-journal>`.
