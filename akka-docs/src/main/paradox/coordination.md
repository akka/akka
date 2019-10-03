---
project.description: A distributed lock with Akka Coordination using a pluggable lease API.
---
# Coordination

Akka Coordination is a set of tools for distributed coordination.

@@project-info{ projectId="akka-coordination" }

## Dependency

@@dependency[sbt,Gradle,Maven] {
  group="com.typesafe.akka"
  artifact="akka-coordination_$scala.binary_version$"
  version="$akka.version$"
}

## Lease

The lease is a pluggable API for a distributed lock. 

## Using a lease

Leases are loaded with:

* Lease name
* Config location to indicate which implementation should be loaded
* Owner name 

Any lease implementation should provide the following guarantees:

* A lease with the same name loaded multiple times, even on different nodes, is the same lease 
* Only one owner can acquire the lease at a time

To acquire a lease:

Scala
:  @@snip [LeaseDocSpec.scala](/akka-coordination/src/test/scala/docs/akka/coordination/LeaseDocSpec.scala) { #lease-usage }

Java
:  @@snip [LeaseDocTest.java](/akka-coordination/src/test/java/jdocs/akka/coordination/lease/LeaseDocTest.java) { #lease-usage }

Acquiring a lease returns a @scala[Future]@java[CompletionStage] as lease implementations typically are implemented 
via a third party system such as the Kubernetes API server or Zookeeper.

Once a lease is acquired `checkLease` can be called to ensure that the lease is still acquired. As lease implementations
are based on other distributed systems a lease can be lost due to a timeout with the third party system. This operation is 
not asynchronous so it can be called before performing any action for which having the lease is important.

A lease has an owner. If the same owner tries to acquire the lease multiple times it will succeed i.e. leases are reentrant. 

It is important to pick a lease name that will be unique for your use case. If a lease needs to be unique for each node
in a Cluster the cluster host port can be use:

Scala
:  @@snip [LeaseDocSpec.scala](/akka-coordination/src/test/scala/docs/akka/coordination/LeaseDocSpec.scala) { #cluster-owner }

Java
:  @@snip [LeaseDocTest.scala](/akka-coordination/src/test/java/jdocs/akka/coordination/lease/LeaseDocTest.java) { #cluster-owner }

For use cases where multiple different leases on the same node then something unique must be added to the name. For example
a lease can be used with Cluster Sharding and in this case the shard Id is included in the lease name for each shard.

### Setting a lease heartbeat

If a node with a lease crashes or is unresponsive the `heartbeat-timeout` is how long before other nodes can acquire the 
the lease. Without this timeout operator intervention would be needed to release a lease in the case of a node crash.
This is the safest option but not practical in all cases.

The value should be greater than the max expected JVM pause e.g. garbage collection, otherwise a lease can be acquired
by another node and then when the original node becomes responsive again there will be a short time before the original lease owner 
can take action e.g. shutdown shards or singletons.

## Usages in other Akka modules

Leases can be used for @ref[Cluster Singletons](cluster-singleton.md#lease) and @ref[Cluster Sharding](cluster-sharding.md#lease). 

## Lease implementations

* [Kubernetes API](https://doc.akka.io/docs/akka-enhancements/current/kubernetes-lease.html)

## Implementing a lease

Implementations should extend
the @scala[`akka.coordination.lease.scaladsl.Lease`]@java[`akka.coordination.lease.javadsl.Lease`] 

Scala
:  @@snip [LeaseDocSpec.scala](/akka-coordination/src/test/scala/docs/akka/coordination/LeaseDocSpec.scala) { #lease-example }

Java
:  @@snip [LeaseDocTest.scala](/akka-coordination/src/test/java/jdocs/akka/coordination/lease/LeaseDocTest.java) { #lease-example }

The methods should provide the following guarantees:

* `acquire` should complete with: `true` if the lease has been acquired, `false` if the lease is taken by another owner, or fail if it can't communicate with the third party system implementing the lease.
* `release` should complete with: `true` if the lease has definitely been released, `false` if the lease has definitely not been released, or fail if it is unknown if the lease has been released.
* `checkLease` should return false until an `acquire` @scala[Future]@java[CompletionStage] has completed and should return `false` if the lease is lost due to an error communicating with the third party. Check lease should also not block.
* The `acquire` lease lost callback should only be called after an `aquire` @scala[Future]@java[CompletionStage] has completed and should be called if the lease is lose e.g. due to losing communication with the third party system.

In addition it is expected that a lease implementation will include a time to live mechanism meaning that a lease won't be held for ever in case the node crashes.
If a user prefers to have outside intervention in this case for maximum safety then the time to live can be set to infinite.

The configuration must define the `lease-class` property for the FQCN of the lease implementation.

The lease implementation should have support for the following properties where the defaults come from `akka.coordination.lease`:

@@snip [reference.conf](/akka-coordination/src/main/resources/reference.conf) { #defaults }

This configuration location is passed into `getLease`.

Scala
:  @@snip [LeaseDocSpec.scala](/akka-coordination/src/test/scala/docs/akka/coordination/LeaseDocSpec.scala) { #lease-config }

Java
:  @@snip [LeaseDocSpec.scala](/akka-coordination/src/test/scala/docs/akka/coordination/LeaseDocSpec.scala) { #lease-config }





