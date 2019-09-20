# Cluster Usage
  
This document describes how to use Akka Cluster and the Cluster APIs. 
For specific documentation topics see: 

* @ref:[Cluster Specification](cluster-concepts.md)
* @ref:[Cluster Membership Service](cluster-membership.md)
* @ref:[When and where to use Akka Cluster](choosing-cluster.md)
* @ref:[Higher level Cluster tools](#higher-level-cluster-tools)
* @ref:[Rolling Updates](../additional/rolling-updates.md)
* @ref:[Operating, Managing, Observability](../additional/operations.md)

## Dependency

To use Akka Cluster add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-typed_$scala.binary_version$
  version=$akka.version$
}

## Cluster API Extension

The Cluster extension gives you access to management tasks such as @ref:[Joining, Leaving and Downing](cluster-membership.md#user-actions)
and subscription of cluster membership events such as @ref:[MemberUp, MemberRemoved and UnreachableMember](cluster-membership.md#membership-lifecycle),
which are exposed as event APIs.  

It does this through these references are on the `Cluster` extension:

* manager: An @scala[`ActorRef[akka.cluster.typed.ClusterCommand]`]@java[`ActorRef<akka.cluster.typed.ClusterCommand>`] where a `ClusterCommand` is a command such as: `Join`, `Leave` and `Down`
* subscriptions: An @scala[`ActorRef[akka.cluster.typed.ClusterStateSubscription]`]@java[`ActorRef<akka.cluster.typed.ClusterStateSubscription>`] where a `ClusterStateSubscription` is one of `GetCurrentState` or `Subscribe` and `Unsubscribe` to cluster events like `MemberRemoved`
* state: The current `CurrentClusterState`

All of the examples below assume the following imports:

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-imports }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-imports }

<a id="basic-cluster-configuration"></a>
And the minimum configuration required is to set a host/port for remoting and the `akka.actor.provider = "cluster"`.

@@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #config-seeds }

Starting the cluster on each node:

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-create }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-create }

@@@ note
  
  The name of the cluster's `ActorSystem` must be the same for all members, which is passed in when you start the `ActorSystem`.

@@@

### Joining and Leaving a Cluster 

If not using configuration to specify [seed nodes to join](#joining-to-seed-nodes), joining the cluster can be done programmatically via the `manager`.

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-join }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-join }

[Leaving](#leaving) the cluster and [downing](#downing) a node are similar:

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-leave }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-leave }

### Cluster Subscriptions

Cluster `subscriptions` can be used to receive messages when cluster state changes. For example, registering
for all `MemberEvent`s, then using the `manager` to have a node leave the cluster will result in events
for the node going through the @ref:[Membership Lifecycle](cluster-membership.md#membership-lifecycle).

This example subscribes to a @scala[`subscriber: ActorRef[MemberEvent]`]@java[`ActorRef<MemberEvent> subscriber`]:

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-subscribe }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-subscribe }

Then asking a node to leave:

Scala
:  @@snip [BasicClusterExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/BasicClusterExampleSpec.scala) { #cluster-leave-example }

Java
:  @@snip [BasicClusterExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/BasicClusterExampleTest.java) { #cluster-leave-example }


### Cluster State

Instead of subscribing to cluster events it can sometimes be convenient to only get the full membership state with
@scala[`Cluster(system).state`]@java[`Cluster.get(system).state()`]. Note that this state is not necessarily in sync with the events published to a
cluster subscription.

See [Cluster Membership](cluster-membership.md#member-events) more information on member events specifically.
There are more types of change events, consult the API documentation
of classes that extends `akka.cluster.ClusterEvent.ClusterDomainEvent` for details about the events.
 
## Cluster Membership API
 
The `akka.cluster.seed-nodes` are initial contact points for [automatically](#joining-automatically-to-seed-nodes-with-cluster-bootstrap)
or [manually](#joining-configured-seed-nodes) joining a cluster.
 
After the joining process the seed nodes are not special and they participate in the cluster in exactly the same
way as other nodes.

### Joining configured seed nodes
 
When a new node is started it sends a message to all seed nodes and then sends a join command to the one that
answers first. If no one of the seed nodes replied (might not be started yet)
it retries this procedure until successful or shutdown.

You define the seed nodes in the [configuration](#configuration) file (application.conf):

This can also be defined as Java system properties when starting the JVM using the following syntax:

```
-Dakka.cluster.seed-nodes.0=akka://ClusterSystem@host1:2552
-Dakka.cluster.seed-nodes.1=akka://ClusterSystem@host2:2552
```

The seed nodes can be started in any order and it is not necessary to have all
seed nodes running, but the node configured as the **first element** in the `seed-nodes`
configuration list must be started when initially starting a cluster, otherwise the
other seed-nodes will not become initialized and no other node can join the cluster.
The reason for the special first seed node is to avoid forming separated islands when
starting from an empty cluster.
It is quickest to start all configured seed nodes at the same time (order doesn't matter),
otherwise it can take up to the configured `seed-node-timeout` until the nodes
can join.

Once more than two seed nodes have been started it is no problem to shut down the first
seed node. If the first seed node is restarted, it will first try to join the other
seed nodes in the existing cluster. Note that if you stop all seed nodes at the same time
and restart them with the same `seed-nodes` configuration they will join themselves and
form a new cluster instead of joining remaining nodes of the existing cluster. That is
likely not desired and should be avoided by listing several nodes as seed nodes for redundancy
and don't stop all of them at the same time.

Note that if you are going to start the nodes on different machines you need to specify the
ip-addresses or host names of the machines in `application.conf` instead of `127.0.0.1`

### Joining automatically to seed nodes with Cluster Bootstrap

Automatic discovery of nodes for the joining process is available
using the open source Akka Management project's module, 
@ref:[Cluster Bootstrap](../additional/operations.md#cluster-bootstrap).
Please refer to its documentation for more details.

### Joining programmatically to seed nodes

@@include[cluster.md](../includes/cluster.md) { #join-seeds-programmatic }
           
### Tuning joins

Unsuccessful attempts to contact seed nodes are automatically retried after the time period defined in
configuration property `seed-node-timeout`. Unsuccessful attempt to join a specific seed node is
automatically retried after the configured `retry-unsuccessful-join-after`. Retrying means that it
tries to contact all seed nodes and then joins the node that answers first. The first node in the list
of seed nodes will join itself if it cannot contact any of the other seed nodes within the
configured `seed-node-timeout`.

The joining of given seed nodes will by default be retried indefinitely until
a successful join. That process can be aborted if unsuccessful by configuring a
timeout. When aborted it will run @ref:[Coordinated Shutdown](../actors.md#coordinated-shutdown),
which by default will terminate the ActorSystem. CoordinatedShutdown can also be configured to exit
the JVM. It is useful to define this timeout if the `seed-nodes` are assembled
dynamically and a restart with new seed-nodes should be tried after unsuccessful
attempts.

```
akka.cluster.shutdown-after-unsuccessful-join-seed-nodes = 20s
akka.coordinated-shutdown.terminate-actor-system = on
```

If you don't configure seed nodes or use one of the join seed node functions you need to join the cluster manually,
which can be performed by using [JMX](#jmx) or [HTTP](#http).

You can join to any node in the cluster. It does not have to be configured as a seed node.
Note that you can only join to an existing cluster member, which means that for bootstrapping some
node must join itself,and then the following nodes could join them to make up a cluster.

An actor system can only join a cluster once. Additional attempts will be ignored.
When it has successfully joined it must be restarted to be able to join another
cluster or to join the same cluster again. It can use the same host name and port
after the restart, when it come up as new incarnation of existing member in the cluster,
trying to join in, then the existing one will be removed from the cluster and then it will
be allowed to join.

<a id="automatic-vs-manual-downing"></a>
### Downing

When a member is considered by the failure detector to be `unreachable` the
leader is not allowed to perform its duties, such as changing status of
new joining members to 'Up'. The node must first become `reachable` again, or the
status of the unreachable member must be changed to 'Down'. Changing status to 'Down'
can be performed automatically or manually. By default it must be done manually, using
@ref:[JMX](../additional/operations.md#jmx) or @ref:[HTTP](../additional/operations.md#http).

It can also be performed programmatically with @scala[`Cluster(system).down(address)`]@java[`Cluster.get(system).down(address)`].

If a node is still running and sees its self as Down it will shutdown. @ref:[Coordinated Shutdown](../actors.md#coordinated-shutdown) will automatically
run if `run-coordinated-shutdown-when-down` is set to `on` (the default) however the node will not try
and leave the cluster gracefully so sharding and singleton migration will not occur.

A production solution for the downing problem is provided by
[Split Brain Resolver](http://developer.lightbend.com/docs/akka-commercial-addons/current/split-brain-resolver.html),
which is part of the [Lightbend Reactive Platform](http://www.lightbend.com/platform).
If you don’t use RP, you should anyway carefully read the [documentation](http://developer.lightbend.com/docs/akka-commercial-addons/current/split-brain-resolver.html)
of the Split Brain Resolver and make sure that the solution you are using handles the concerns
described there.

### Auto-downing (DO NOT USE)

There is an automatic downing feature that you should not use in production. For testing you can enable it with configuration:

```
akka.cluster.auto-down-unreachable-after = 120s
```

This means that the cluster leader member will change the `unreachable` node
status to `down` automatically after the configured time of unreachability.

This is a naïve approach to remove unreachable nodes from the cluster membership.
It can be useful during development but in a production environment it will eventually breakdown the cluster. 
When a network partition occurs, both sides of the partition will see the other side as unreachable and remove it from the cluster.
This results in the formation of two separate, disconnected, clusters (known as *Split Brain*).

This behaviour is not limited to network partitions. It can also occur if a node
in the cluster is overloaded, or experiences a long GC pause.

@@@ warning

We recommend against using the auto-down feature of Akka Cluster in production. It
has multiple undesirable consequences for production systems.

If you are using @ref:[Cluster Singleton](cluster-singleton.md) or @ref:[Cluster Sharding](cluster-sharding.md) it can break the contract provided by 
those features. Both provide a guarantee that an actor will be unique in a cluster.
With the auto-down feature enabled, it is possible for multiple independent clusters
to form (*Split Brain*). When this happens the guaranteed uniqueness will no
longer be true resulting in undesirable behaviour in the system.

This is even more severe when @ref:[Akka Persistence](persistence.md) is used in
conjunction with Cluster Sharding. In this case, the lack of unique actors can 
cause multiple actors to write to the same journal. Akka Persistence operates on a
single writer principle. Having multiple writers will corrupt the journal
and make it unusable. 

Finally, even if you don't use features such as Persistence, Sharding, or Singletons, 
auto-downing can lead the system to form multiple small clusters. These small
clusters will be independent from each other. They will be unable to communicate
and as a result you may experience performance degradation. Once this condition
occurs, it will require manual intervention in order to reform the cluster.

Because of these issues, auto-downing should **never** be used in a production environment.

@@@

### Leaving

There are two ways to remove a member from the cluster.

1. The recommended way to leave a cluster is a graceful exit, informing the cluster that a node shall leave. 
This can be performed using @ref:[JMX](../additional/operations.md#jmx) or @ref:[HTTP](../additional/operations.md#http). 
This method will offer faster hand off to peer nodes during node shutdown.
1. When a graceful exit is not possible, you can stop the actor system (or the JVM process, for example a SIGTERM sent from the environment). It will be detected
as unreachable and removed after the automatic or manual downing.

The @ref:[Coordinated Shutdown](../actors.md#coordinated-shutdown) will automatically run when the cluster node sees itself as
`Exiting`, i.e. leaving from another node will trigger the shutdown process on the leaving node.
Tasks for graceful leaving of cluster including graceful shutdown of Cluster Singletons and
Cluster Sharding are added automatically when Akka Cluster is used, i.e. running the shutdown
process will also trigger the graceful leaving if it's not already in progress.

Normally this is handled automatically, but in case of network failures during this process it might still
be necessary to set the node’s status to `Down` in order to complete the removal. For handling network failures
see [Split Brain Resolver](http://developer.lightbend.com/docs/akka-commercial-addons/current/split-brain-resolver.html),
part of the [Lightbend Reactive Platform](http://www.lightbend.com/platform).

## Serialization 
 
Enable @ref:[serialization](../serialization.md) to send events between ActorSystems.
@ref:[Serialization with Jackson](../serialization-jackson.md) is a good choice in many cases, and our
recommendation if you don't have other preferences or constraints.
 
Actor references are typically included in the messages, since there is no `sender`. 
To serialize actor references to/from string representation you would use the `ActorRefResolver`.
For example here's how a serializer could look for `Ping` and `Pong` messages:

Scala
:  @@snip [PingSerializer.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/PingSerializer.scala) { #serializer }

Java
:  @@snip [PingSerializerExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/PingSerializerExampleTest.java) { #serializer }

You can look at the
@java[@extref[Cluster example project](samples:akka-samples-cluster-java)]
@scala[@extref[Cluster example project](samples:akka-samples-cluster-scala)]
to see what this looks like in practice.

## Node Roles

Not all nodes of a cluster need to perform the same function: there might be one sub-set which runs the web front-end,
one which runs the data access layer and one for the number-crunching. Deployment of actors, for example by cluster-aware
routers, can take node roles into account to achieve this distribution of responsibilities.

The node roles are defined in the configuration property named `akka.cluster.roles`
and typically defined in the start script as a system property or environment variable.

The roles are part of the membership information in `MemberEvent` that you can subscribe to.

## Failure Detector

The nodes in the cluster monitor each other by sending heartbeats to detect if a node is
unreachable from the rest of the cluster. Please see:

* @ref:[Failure Detector specification](cluster-concepts.md#failure-detector)
* @ref:[Phi Accrual Failure Detector](failure-detector.md) implementation
* [Using the Failure Detector](#using-the-failure-detector) 
 
### Using the Failure Detector
 
Cluster uses the `akka.remote.PhiAccrualFailureDetector` failure detector by default, or you can provide your by
implementing the `akka.remote.FailureDetector` and configuring it:

```
akka.cluster.implementation-class = "com.example.CustomFailureDetector"
```

In the [Cluster Configuration](#configuration) you may want to adjust these
depending on you environment:

* When a *phi* value is considered to be a failure `akka.cluster.failure-detector.threshold`
* Margin of error for sudden abnormalities `akka.cluster.failure-detector.acceptable-heartbeat-pause`  

## How to test

Akka comes with and uses several types of testing strategies:

* @ref:[Testing](testing.md)
* @ref:[Multi Node Testing](../multi-node-testing.md)
* @ref:[Multi JVM Testing](../multi-jvm-testing.md)

## Configuration

There are several configuration properties for the cluster. Refer to the 
@ref:[reference configuration](../general/configuration.md#config-akka-cluster) for full
configuration descriptions, default values and options.

### How To Startup when a Cluster size is reached

A common use case is to start actors after the cluster has been initialized,
members have joined, and the cluster has reached a certain size.

With a configuration option you can define required number of members
before the leader changes member status of 'Joining' members to 'Up'.:

```
akka.cluster.min-nr-of-members = 3
```

In a similar way you can define required number of members of a certain role
before the leader changes member status of 'Joining' members to 'Up'.:

```
akka.cluster.role {
  frontend.min-nr-of-members = 1
  backend.min-nr-of-members = 2
}
```

### Cluster Info Logging

You can silence the logging of cluster events at info level with configuration property:

```
akka.cluster.log-info = off
```

You can enable verbose logging of cluster events at info level, e.g. for temporary troubleshooting, with configuration property:

```
akka.cluster.log-info-verbose = on
```

### Cluster Dispatcher

The cluster extension is implemented with actors. To protect them against
disturbance from user actors they are by default run on the internal dispatcher configured
under `akka.actor.internal-dispatcher`. The cluster actors can potentially be isolated even
further, onto their own dispatcher using the setting `akka.cluster.use-dispatcher`
or made run on the same dispatcher to keep the number of threads down.

### Configuration Compatibility Check

Creating a cluster is about deploying two or more nodes and make then behave as if they were one single application. Therefore it's extremely important that all nodes in a cluster are configured with compatible settings. 

The Configuration Compatibility Check feature ensures that all nodes in a cluster have a compatible configuration. Whenever a new node is joining an existing cluster, a subset of its configuration settings (only those that are required to be checked) is sent to the nodes in the cluster for verification. Once the configuration is checked on the cluster side, the cluster sends back its own set of required configuration settings. The joining node will then verify if it's compliant with the cluster configuration. The joining node will only proceed if all checks pass, on both sides.   

New custom checkers can be added by extending `akka.cluster.JoinConfigCompatChecker` and including them in the configuration. Each checker must be associated with a unique key:

```
akka.cluster.configuration-compatibility-check.checkers {
  my-custom-config = "com.company.MyCustomJoinConfigCompatChecker"
}
``` 

@@@ note

Configuration Compatibility Check is enabled by default, but can be disabled by setting `akka.cluster.configuration-compatibility-check.enforce-on-join = off`. This is specially useful when performing rolling updates. Obviously this should only be done if a complete cluster shutdown isn't an option. A cluster with nodes with different configuration settings may lead to data loss or data corruption. 

This setting should only be disabled on the joining nodes. The checks are always performed on both sides, and warnings are logged. In case of incompatibilities, it is the responsibility of the joining node to decide if the process should be interrupted or not.  

If you are performing a rolling update on cluster using Akka 2.5.9 or prior (thus, not supporting this feature), the checks will not be performed because the running cluster has no means to verify the configuration sent by the joining node, nor to send back its own configuration.  

@@@ 

## Higher level Cluster tools

@@include[cluster.md](../includes/cluster.md) { #cluster-singleton } 
See @ref:[Cluster Singleton](cluster-singleton.md).
 
@@include[cluster.md](../includes/cluster.md) { #cluster-sharding }  
See @ref:[Cluster Sharding](cluster-sharding.md).
 
@@include[cluster.md](../includes/cluster.md) { #cluster-ddata } 
See @ref:[Distributed Data](distributed-data.md).

@@include[cluster.md](../includes/cluster.md) { #cluster-pubsub } 
Classic Pub Sub can be used by leveraging the `.toClassic` adapters.
See @ref:[Distributed Publish Subscribe in Cluster](../distributed-pub-sub.md). The API is @github[#26338](#26338).

@@include[cluster.md](../includes/cluster.md) { #cluster-multidc }
See @ref:[Cluster Multi-DC](../cluster-dc.md). The API for multi-dc sharding is @github[#27705](#27705).
