.. _migration-2.3:

################################
 Migration Guide 2.2.x to 2.3.x
################################

The 2.2 release contains some structural changes that require some
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


