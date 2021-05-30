# Classic Multi-DC Cluster

This chapter describes how @ref[Akka Cluster](cluster-usage.md) can be used across
multiple data centers, availability zones or regions.

For the full documentation of this feature and for new projects see @ref:[Multi-DC Cluster](typed/cluster-dc.md).

## Membership

You can retrieve information about what data center a member belongs to:

Scala
:  @@snip [ClusterDocSpec.scala](/akka-docs/src/test/scala/docs/cluster/ClusterDocSpec.scala) { #dcAccess }

Java
:  @@snip [ClusterDocTest.java](/akka-docs/src/test/java/jdocs/cluster/ClusterDocTest.java) { #dcAccess }

For the full documentation of this feature and for new projects see @ref:[Multi-DC Cluster](typed/cluster-dc.md#membership).

## Cluster Singleton

This is how to create a singleton proxy for a specific data center:

Scala
:  @@snip [ClusterSingletonManagerSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/singleton/ClusterSingletonManagerSpec.scala) { #create-singleton-proxy-dc }

Java
:  @@snip [ClusterSingletonManagerTest.java](/akka-cluster-tools/src/test/java/akka/cluster/singleton/ClusterSingletonManagerTest.java) { #create-singleton-proxy-dc }

If using the own data center as the `withDataCenter` parameter that would be a proxy for the singleton in the own data center, which
is also the default if `withDataCenter` is not given.

For the full documentation of this feature and for new projects see @ref:[Multi-DC Cluster](typed/cluster-dc.md#cluster-singleton).

## Cluster Sharding

This is how to create a sharding proxy for a specific data center:

Scala
:  @@snip [ClusterShardingSpec.scala](/akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala) { #proxy-dc }

Java
:  @@snip [ClusterShardingTest.java](/akka-docs/src/test/java/jdocs/sharding/ClusterShardingTest.java) { #proxy-dc }

For the full documentation of this feature and for new projects see @ref:[Multi-DC Cluster](typed/cluster-dc.md#cluster-sharding).
