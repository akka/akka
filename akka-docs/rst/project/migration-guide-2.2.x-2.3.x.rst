.. _migration-2.3:

################################
 Migration Guide 2.2.x to 2.3.x
################################

The 2.3 release contains some structural changes that require some
simple, mechanical source-level changes in client code.

When migrating from earlier versions you should first follow the instructions for
migrating :ref:`1.3.x to 2.0.x <migration-2.0>` and then :ref:`2.0.x to 2.1.x <migration-2.1>`
and then :ref:`2.1.x to 2.2.x <migration-2.2>`.

Removed hand over data in cluster singleton
===========================================

The support for passing data from previous singleton instance to new instance
in a graceful leaving scenario has been removed. Valuable state should be persisted
in durable storage instead, e.g. using akka-persistence. The constructor/props parameters
of ``ClusterSingletonManager`` has been changed to ordinary ``Props`` parameter for the
singleton actor instead of the factory parameter.

Changed cluster auto-down configuration
=======================================

``akka.cluster.auto-down`` setting has been replaced by ``akka.cluster.auto-down-unreachable-after``,
which instructs the cluster to automatically mark unreachable nodes as DOWN after this
configured time of unreachability. This feature is disabled by default, as it also was in 2.2.x.

During the deprecation phase ``akka.cluster.auto-down=on`` is interpreted at as instant auto-down.

Routers
=======

The routers have been cleaned up and enhanced. The routing logic has been extracted to be usable within
normal actors as well. Some usability problems have been have been solved, such as properly reject invalid
configuration combinations. Routees can be dynamically added and removed by sending special management messages
to the router.

The two types of routers have been named ``Pool`` and ``Group`` to make them more distinguishable and reduce confusion
of their subtle differences:

* Pool - The router creates routees as child actors and removes them from the router if they
  terminate.
  
* Group - The routee actors are created externally to the router and the router sends
  messages to the specified path using actor selection, without watching for termination.

Configuration of routers is compatible with 2.2.x, but the ``router`` type should preferably be specified
with ``-pool`` or ``-group`` suffix.

Some classes used for programmatic definition of routers have been renamed, but the old classes remain as
deprecated. The compiler will guide you with deprecation warning. For example ``RoundRobinRouter`` has
been renamed to ``RoundRobinPool`` or ``RoundRobinGroup`` depending on which type you are actually using.

There is no replacement for ``SmallestMailboxRouter`` combined with routee paths, i.e. a group, because that
combination is not useful.

An optional API enhancement that makes the code read better is to use the ``props`` method instead of ``withRouter``.
``withRouter`` has not been deprecated and you can continue to use that if you prefer that way of defining a router. 

Example in Scala::

    context.actorOf(FromConfig.props(Props[Worker]), "router1")
    context.actorOf(RoundRobinPool(5).props(Props[Worker]), "router2") 

Example in Java::

    getContext().actorOf(FromConfig.getInstance().props(Props.create(Worker.class)), 
      "router1");
      
    getContext().actorOf(new RoundRobinPool(5).props(Props.create(Worker.class)), 
        "router2");

To support multiple routee paths for a cluster aware router sending to paths the deployment configuration
property ``cluster.routees-path`` has been changed to string list ``routees.paths`` property.
The old ``cluster.routees-path`` is deprecated, but still working during the deprecation phase.

Example::

    /router4 {
      router = round-robin-group
      nr-of-instances = 10
      routees.paths = ["/user/myserviceA", "/user/myserviceB"]
      cluster.enabled = on
    }

The API for creating custom routers and resizers have changed without keeping the old API as deprecated. 
That should be a an API used by only a few users and they should be able to migrate to the new API
without much trouble.

Read more about the new routers in the :ref:`documentation for Scala <routing-scala>` and 
:ref:`documentation for Java <routing-java>`.

Akka IO is no longer experimental
=================================

The core IO layer introduced in Akka 2.2 is now a fully supported module of Akka.

Experimental Pipelines IO abstraction has been removed
======================================================

Pipelines in the form introduced by 2.2 has been found unintuitive and are therefore discontinued.
A new more flexible and easier-to-use abstraction will replace their role in the future. Pipelines
will be still available in the 2.2 series.

Changed cluster expected-response-after configuration
=====================================================

Configuration property ``akka.cluster.failure-detector.heartbeat-request.expected-response-after`` 
has been renamed to ``akka.cluster.failure-detector.expected-response-after``.

Removed automatic retry feature from Remoting in favor of retry-gate
====================================================================

The retry-gate feature is now the only failure handling strategy in Remoting. This change means that when remoting detects faulty
connections it goes into a gated state where all buffered and subsequent remote messages are dropped until the configurable
time defined by the configuration key ``akka.remote.retry-gate-closed-for`` elapses after the failure event. This
behavior prevents reconnect storms and unbounded buffer growth during network instabilities. After the configured
time elapses the gate is lifted and a new connection will be attempted when there are new remote messages to be
delivered.

In concert with this change all settings related to the old reconnect behavior (``akka.remote.retry-window`` and
``akka.remote.maximum-retries-in-window``) were removed.

The timeout setting ``akka.remote.gate-invalid-addresses-for`` that controlled the gate interval for certain failure
events is also removed and all gating intervals are now controlled by the ``akka.remote.retry-gate-closed-for`` setting
instead.

Reduced default sensitivity settings for transport failure detector in Remoting
===============================================================================

Since the most commonly used transport with Remoting is TCP, which provides proper connection termination events the failure detector sensitivity
setting ``akka.remote.transport-failure-detector.acceptable-heartbeat-pause`` now defaults to 20 seconds to reduce load induced
false-positive failure detection events in remoting. In case a non-connection-oriented protocol is used it is recommended
to change this and the ``akka.remote.transport-failure-detector.heartbeat-interval`` setting to a more sensitive value.

Quarantine is now permanent
===========================

The setting that controlled the length of quarantine ``akka.remote.quarantine-systems-for`` has been removed. The only
setting available now is ``akka.remote.prune-quarantine-marker-after`` which influences how long quarantine tombstones
are kept around to avoid long-term memory leaks. This new setting defaults to 5 days.

Remoting uses a dedicated dispatcher by default
===============================================

The default value of ``akka.remote.use-dispatcher`` has been changed to a dedicated dispatcher.

Dataflow is Deprecated
======================

Akka dataflow is superseded by `Scala Async <https://github.com/scala/async>`_.

Durable Mailboxes are Deprecated
================================

Durable mailboxes are superseded by ``akka-persistence``, which offers several
tools to support reliable messaging.

Read more about ``akka-persistence`` in the :ref:`documentation for Scala <persistence-scala>` and 
:ref:`documentation for Java <persistence-java>`.

Deprecated STM Support for Agents
=================================

Agents participating in enclosing STM transaction is a deprecated feature.

Transactor Module is Deprecated
===============================

The integration between actors and STM in the module ``akka-transactor`` is deprecated and will be
removed in a future version.

Typed Channels has been removed
===============================

Typed channels were an experimental feature which we decided to remove: its implementation relied
on an experimental feature of Scala for which there is no correspondence in Java and other languages and
its usage was not intuitive.

Removed Deprecated Features
===========================

The following, previously deprecated, features have been removed:

 * `event-handlers renamed to loggers <http://doc.akka.io/docs/akka/2.2.3/project/migration-guide-2.1.x-2.2.x.html#event-handlers_renamed_to_loggers>`_ 
 * `API changes to FSM and TestFSMRef <http://doc.akka.io/docs/akka/2.2.3/project/migration-guide-2.1.x-2.2.x.html#API_changes_to_FSM_and_TestFSMRef>`_
 * DefaultScheduler superseded by LightArrayRevolverScheduler
 * all previously deprecated construction and deconstruction methods for Props
 
publishCurrentClusterState is Deprecated
========================================

Use ``sendCurrentClusterState`` instead. Note that you can also retrieve the current cluster state
with the new ``Cluster(system).state``.


CurrentClusterState is not a ClusterDomainEvent
===============================================

``CurrentClusterState`` does not implement the ``ClusterDomainEvent`` marker interface any more.

Note the new ``initialStateMode`` parameter of ``Cluster.subscribe``, which makes it possible
to handle the initial state as events instead of ``CurrentClusterState``. See 
:ref:`documentation for Scala <cluster_subscriber_scala>` and 
:ref:`documentation for Java <cluster_subscriber_java>`.


BalancingDispatcher is Deprecated
=================================

Use ``BalancingPool`` instead of ``BalancingDispatcher``. See :ref:`documentation for Scala <balancing-pool-scala>` and 
:ref:`documentation for Java <balancing-pool-java>`.

During a migration period you can still use BalancingDispatcher by specifying the full class name in the dispatcher configuration::

    type = "akka.dispatch.BalancingDispatcherConfigurator"

akka-sbt-plugin is Removed
==========================

``akka-sbt-plugin`` for packaging of application binaries has been removed. Version 2.2.3 can still be used
independent of Akka version of the application. Version 2.2.3 can be used with both sbt 0.12 and 0.13.

`sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_ is the recommended tool for creating
distributions of Akka applications when using sbt.

Parens Added to sender
======================

Parens were added to the ``sender()`` method of the Actor Scala API to highlight that the ``sender()`` reference is not referentially transparent and must not be exposed to other threads, for example by closing over it when using future callbacks.

It is recommended to use this new convention::

    sender() ! "reply"

However, it is not mandatory to use parens and you do not have to change anything.

ReliableProxy Constructor Changed
=================================

The constructor of ``ReliableProxy`` in ``akka-contrib`` has been changed to take an ``ActorPath`` instead of
an ``ActorRef``.  Also it takes new parameters to support reconnection.  Use the new props factory methods, ``ReliableProxy.props``.

OSGi Changes
============

``akka-osgi`` no longer contains the ``akka-actor`` classes, instead
``akka-actor`` is a bundle now. ``akka-osgi`` only contains a few OSGi helpers,
most notably the ``BundleDelegatingClassLoader`` which resolves e.g.
``reference.conf`` files (hence these are not copied into the ``akka-osgi``
bundle any longer either).

``akka-osgi-aries`` has been removed. Similar can be implemented outside of Akka if needed.
 
TestKit: reworked time dilation
===============================

``TestDuration`` has been changed into an implicit value class plus a Java API in JavaTestKit. Please change::

    import akka.testkit.duration2TestDuration

into::

    import akka.testkit.TestDuration

