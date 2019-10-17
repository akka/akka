---
project.description: Migrating to Akka 2.6.
---
# Migration Guide 2.5.x to 2.6.x

Akka 2.6.x is binary backwards compatible with 2.5.x with the ordinary exceptions listed in the
@ref:[Binary Compatibility Rules](../common/circuitbreaker.md).

This means that updating an application from Akka 2.5.x to 2.6.x should be a smooth process, and
that libraries built for Akka 2.5.x can also be used with Akka 2.6.x. For example Akka HTTP 10.1.10
and Akka Management 1.0.3 can be used with Akka 2.6.0 dependencies. You may have to add explicit
dependencies to the new Akka version in your build.

That said, there are some changes to configuration and behavior that should be considered, so
reading this migration guide and testing your application thoroughly is recommended.

Rolling updates are possible without shutting down all nodes of the Akka Cluster, but will require
configuration adjustments as described in the @ref:[Remoting](#remoting) section of this migration
guide.

## Scala 2.11 no longer supported

If you are still using Scala 2.11 then you must upgrade to 2.12 or 2.13

## Auto-downing removed

Auto-downing of unreachable Cluster members have been removed after warnings and recommendations against using it
for many years. It was by default disabled, but could be enabled with configuration
`akka.cluster.auto-down-unreachable-after`.

For alternatives see the @ref:[documentation about Downing](../typed/cluster.md#downing).

Auto-downing was a naïve approach to remove unreachable nodes from the cluster membership.
In a production environment it will eventually break down the cluster. 
When a network partition occurs, both sides of the partition will see the other side as unreachable
and remove it from the cluster. This results in the formation of two separate, disconnected, clusters
(known as *Split Brain*).

This behavior is not limited to network partitions. It can also occur if a node in the cluster is
overloaded, or experiences a long GC pause.

When using @ref:[Cluster Singleton](../typed/cluster-singleton.md) or @ref:[Cluster Sharding](../typed/cluster-sharding.md)
it can break the contract provided by those features. Both provide a guarantee that an actor will be unique in a cluster.
With the auto-down feature enabled, it is possible for multiple independent clusters to form (*Split Brain*).
When this happens the guaranteed uniqueness will no longer be true resulting in undesirable behavior in the system.

This is even more severe when @ref:[Akka Persistence](../typed/persistence.md) is used in conjunction with
Cluster Sharding. In this case, the lack of unique actors can cause multiple actors to write to the same journal.
Akka Persistence operates on a single writer principle. Having multiple writers will corrupt the journal
and make it unusable. 

Finally, even if you don't use features such as Persistence, Sharding, or Singletons, auto-downing can lead the
system to form multiple small clusters. These small clusters will be independent from each other. They will be
unable to communicate and as a result you may experience performance degradation. Once this condition occurs,
it will require manual intervention in order to reform the cluster.

Because of these issues, auto-downing should **never** be used in a production environment.

## Removed features that were deprecated

After being deprecated since 2.5.0, the following have been removed in Akka 2.6.0.

* akka-camel module
    - As an alternative we recommend [Alpakka](https://doc.akka.io/docs/alpakka/current/).
    - This is of course not a drop-in replacement. If there is community interest we are open to setting up akka-camel as a separate community-maintained repository.
* akka-agent module
    - If there is interest it may be moved to a separate, community-maintained repository.
* akka-contrib module
    - To migrate, take the components you are using from [Akka 2.5](https://github.com/akka/akka/tree/release-2.5/akka-contrib) and include them in your own project or library under your own package name.
* Actor DSL
    - Actor DSL is a rarely used feature. Use plain `system.actorOf` instead of the DSL to create Actors if you have been using it.
* `akka.stream.extra.Timing` operator
    - If you need it you can now find it in `akka.stream.contrib.Timed` from [Akka Stream Contrib](https://github.com/akka/akka-stream-contrib/blob/master/src/main/scala/akka/stream/contrib/Timed.scala).
* Netty UDP (Classic remoting over UDP)
    - To continue to use UDP configure @ref[Artery UDP](../remoting-artery.md#configuring-ssl-tls-for-akka-remoting) or migrate to Artery TCP.
    - A full cluster restart is required to change to Artery.
* `UntypedActor`
    - Use `AbstractActor` instead.
* `JavaTestKit`
    - Use `akka.testkit.javadsl.TestKit` instead.
* `UntypedPersistentActor`
    - Use `AbstractPersistentActor` instead.
* `UntypedPersistentActorWithAtLeastOnceDelivery`
    - Use @apidoc[AbstractPersistentActorWithAtLeastOnceDelivery] instead.
* `akka.stream.actor.ActorSubscriber` and `akka.stream.actor.ActorPublisher`
    - Use `GraphStage` instead.

After being deprecated since 2.4.0, the following have been removed in Akka 2.6.0.

* Secure cookie in Classic Akka Remoting

After being deprecated since 2.2, the following have been removed in Akka 2.6.0.

* `actorFor`
    - Use `ActorSelection` instead.

### Removed methods

* `Logging.getLogger(UntypedActor)` `UntypedActor` has been removed, use `AbstractActor` instead.
* `LoggingReceive.create(Receive, ActorContext)` use `AbstractActor.Receive` instead.
* `ActorMaterialzierSettings.withAutoFusing` disabling fusing is no longer possible.
* `AbstractActor.getChild` use `findChild` instead.
* `Actor.getRef` use `Actor.getActorRef` instead.
* `CircuitBreaker.onOpen` use `CircuitBreaker.addOnOpenListener`
* `CircuitBreaker.onHalfOpen` use `CircuitBreaker.addOnHalfOpenListener`
* `CircuitBreaker.onClose` use `CircuitBreaker.addOnCloseListener`
* `Source.actorSubscriber`, use `Source.fromGraph` instead.
* `Source.actorActorPublisher`, use `Source.fromGraph` instead.


## Deprecated features

### PersistentFSM

@ref[Migration guide to Persistence Typed](../persistence-fsm.md) is in the PersistentFSM documentation.

### TypedActor

`akka.actor.TypedActor` has been deprecated as of 2.6.0 in favor of the
`akka.actor.typed` API which should be used instead.

There are several reasons for phasing out the old `TypedActor`. The primary reason is they use transparent
remoting which is not our recommended way of implementing and interacting with actors. Transparent remoting
is when you try to make remote method invocations look like local calls. In contrast we believe in location
transparency with explicit messaging between actors (same type of messaging for both local and remote actors).
They also have limited functionality compared to ordinary actors, and worse performance.

To summarize the fallacy of transparent remoting:
* Was used in CORBA, RMI, and DCOM, and all of them failed. Those problems were noted by [Waldo et al already in 1994](http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.41.7628)
* Partial failure is a major problem. Remote calls introduce uncertainty whether the function was invoked or not.
  Typically handled by using timeouts but the client can't always know the result of the call.
* Latency of calls over a network are several order of magnitudes longer than latency of local calls,
  which can be more than surprising if encoded as an innocent looking local method call.
* Remote invocations have much lower throughput due to the need of serializing the
  data and you can't just pass huge datasets in the same way.

Therefore explicit message passing is preferred. It looks different from local method calls
(@scala[`actorRef ! message`]@java[`actorRef.tell(message)`]) and there is no misconception
that sending a message will result in it being processed instantaneously. The goal of location
transparency is to unify message passing for both local and remote interactions, versus attempting
to make remote interactions look like local method calls.

Warnings about `TypedActor` have been [mentioned in documentation](https://doc.akka.io/docs/akka/2.5/typed-actors.html#when-to-use-typed-actors)
for many years.

### Cluster Client

Cluster client has been deprecated as of 2.6.0 in favor of [Akka gRPC](https://doc.akka.io/docs/akka-grpc/current/index.html).
It is not advised to build new applications with Cluster client, and existing users @ref[should migrate to Akka gRPC](../cluster-client.md#migration-to-akka-grpc).

### akka-protobuf

`akka-protobuf` was never intended to be used by end users but perhaps this was not well-documented.
Applications should use standard Protobuf dependency instead of `akka-protobuf`. The artifact is still
published, but the transitive dependency to `akka-protobuf` has been removed.

Akka is now using Protobuf version 3.9.0 for serialization of messages defined by Akka.

### ByteString.empty

It is now recommended to use @apidoc[akka.util.ByteString]`.emptyByteString()` instead of
@apidoc[akka.util.ByteString]`.empty()` when using Java because @apidoc[akka.util.ByteString]`.empty()`
is [no longer available as a static method](https://github.com/scala/bug/issues/11509) in the artifacts built for Scala 2.13.

### AkkaSslConfig

`AkkaSslConfig` has been deprecated in favor of setting up TLS with `javax.net.ssl.SSLEngine` directly.

This also means that methods Akka Streams `TLS` and `Tcp` that take `SSLContext` or `AkkaSslConfig` have been
deprecated and replaced with corresponding methods that takes a factory function for creating the `SSLEngine`.

See documentation of @ref:[streaming IO with TLS](../stream/stream-io.md#tls).    

### JavaLogger

`akka.event.jul.JavaLogger` for integration with `java.util.logging` has been deprecated. Use SLF4J instead,
which also has support for `java.util.logging`.

### akka.Main

`akka.Main` is deprecated in favour of starting the `ActorSystem` from a custom main class instead. `akka.Main` was not
adding much value and typically a custom main class is needed anyway.

### Pluggable DNS

Plugging in your own DNS implementation is now deprecated and will be removed in `2.7.0`, it was originally added to
support a third party DNS provided that supported SRV records. The built in `async-dns` now supports SRV records.

The `resolve` and `cached` methods on the `DNS` extension have also been deprecated in favour of ones that take in
`DnsProtocol.Resolve`. These methods return a new types that include SRV records.

## Remoting

### Default remoting is now Artery TCP

@ref[Artery TCP](../remoting-artery.md) is now the default remoting implementation.
Classic remoting has been deprecated and will be removed in `2.7.0`.


<a id="classic-to-artery"></a>
#### Migrating from classic remoting to Artery

Artery has the same functionality as classic remoting and you should normally only have to change the
configuration to switch.

To switch a full cluster restart is required and any overrides for classic remoting need to be ported to Artery configuration.
Artery has a completely different protocol, which means that a rolling update is not supported.

Artery defaults to TCP (see @ref:[selected transport](../remoting-artery.md#selecting-a-transport)) which is a good start
when migrating from classic remoting.

The protocol part in the Akka `Address`, for example `"akka.tcp://actorSystemName@10.0.0.1:2552/user/actorName"`
has changed from `akka.tcp` to `akka`. If you have configured or hardcoded any such addresses you have to change
them to `"akka://actorSystemName@10.0.0.1:25520/user/actorName"`. `akka` is used also when TLS is enabled.
One typical place where such address is used is in the `seed-nodes` configuration.

The default port is 25520 instead of 2552 to avoid connections between Artery and classic remoting due to
misconfiguration. You can run Artery on 2552 if you prefer that (e.g. existing firewall rules) and then you
have to configure the port with:

```
akka.remote.artery.canonical.port = 2552
```

The configuration for Artery is different, so you might have to revisit any custom configuration. See the full
@ref:[reference configuration for Artery](../general/configuration-reference.md#config-akka-remote-artery) and
@ref:[reference configuration for classic remoting](../general/configuration-reference.md#config-akka-remote).

Configuration that is likely required to be ported:

* `akka.remote.netty.tcp.hostname` => `akka.remote.artery.canonical.hostname`
* `akka.remote.netty.tcp.port`=> `akka.remote.artery.canonical.port`

If using SSL then `tcp-tls` needs to be enabled and setup. See @ref[Artery docs for SSL](../remoting-artery.md#configuring-ssl-tls-for-akka-remoting)
for how to do this.

The following events that are published to the `eventStream` have changed:

* classic `akka.remote.QuarantinedEvent` is `akka.remote.artery.QuarantinedEvent` in Artery
* classic `akka.remote.GracefulShutdownQuarantinedEvent` is `akka.remote.artery.GracefulShutdownQuarantinedEvent` in Artery
* classic `akka.remote.ThisActorSystemQuarantinedEvent` is `akka.remote.artery.ThisActorSystemQuarantinedEvent` in Artery

#### Migration from 2.5.x Artery to 2.6.x Artery

The following defaults have changed:

* `akka.remote.artery.transport` default has changed from `aeron-udp` to `tcp`

The following properties have moved. If you don't adjust these from their defaults no changes are required:

For Aeron-UDP:

* `akka.remote.artery.log-aeron-counters` to `akka.remote.artery.advanced.aeron.log-aeron-counters`
* `akka.remote.artery.advanced.embedded-media-driver` to `akka.remote.artery.advanced.aeron.embedded-media-driver`
* `akka.remote.artery.advanced.aeron-dir` to `akka.remote.artery.advanced.aeron.aeron-dir`
* `akka.remote.artery.advanced.delete-aeron-dir` to `akka.remote.artery.advanced.aeron.aeron-delete-dir`
* `akka.remote.artery.advanced.idle-cpu-level` to `akka.remote.artery.advanced.aeron.idle-cpu-level`
* `akka.remote.artery.advanced.give-up-message-after` to `akka.remote.artery.advanced.aeron.give-up-message-after`
* `akka.remote.artery.advanced.client-liveness-timeout` to `akka.remote.artery.advanced.aeron.client-liveness-timeout`
* `akka.remote.artery.advanced.image-liveless-timeout` to `akka.remote.artery.advanced.aeron.image-liveness-timeout`
* `akka.remote.artery.advanced.driver-timeout` to `akka.remote.artery.advanced.aeron.driver-timeout`

For TCP:

* `akka.remote.artery.advanced.connection-timeout` to `akka.remote.artery.advanced.tcp.connection-timeout`


#### Remaining with Classic remoting (not recommended)

Classic remoting is deprecated but can be used in 2.6.x Rolling update from Classic remoting to Artery is
not supported so if you want to update from Akka 2.5.x with Classic remoting to Akka 2.6.x without a full shut
down of the Cluster you have to enable Classic remoting. Later, you can plan for a full shutdown and
@ref:[migrate from classic remoting to Artery](#migrating-from-classic-remoting-to-artery) as a separate step.

Explicitly disable Artery by setting property `akka.remote.artery.enabled` to `false`. Further, any configuration under `akka.remote` that is
specific to classic remoting needs to be moved to `akka.remote.classic`. To see which configuration options
are specific to classic search for them in: @ref:[`akka-remote/reference.conf`](../general/configuration-reference.md#config-akka-remote).

### Persistent mode for Cluster Sharding

Cluster Sharding coordinator and [Remembering Entities](../cluster/cluster-sharding.md#remembering-entities) state could previously be stored in Distributed Data or via Akka Persistence.
The Persistence mode has been deprecated in favour of using the Distributed Data mode for the coordinator state. A replacement for the state
for Remembered Entities is tracked in [issue 27763](https://github.com/akka/akka/issues/27763).

## Java Serialization

Java serialization is known to be slow and [prone to attacks](https://community.hpe.com/t5/Security-Research/The-perils-of-Java-deserialization/ba-p/6838995)
of various kinds - it never was designed for high throughput messaging after all.
One may think that network bandwidth and latency limit the performance of remote messaging, but serialization is a more typical bottleneck.

From Akka 2.6.0 the Akka serialization with Java serialization is disabled by default and Akka
itself doesn't use Java serialization for any of its internal messages.

You have to enable @ref:[serialization](../serialization.md)  to send messages between ActorSystems (nodes) in the Cluster.
@ref:[Serialization with Jackson](../serialization-jackson.md) is a good choice in many cases, and our
recommendation if you don't have other preferences or constraints.

For compatibility with older systems that rely on Java serialization it can be enabled with the following configuration:

```ruby
akka.actor.allow-java-serialization = on
```

Akka will still log warning when Java serialization is used and to silent that you may add:

```ruby
akka.actor.warn-about-java-serializer-usage = off
```

### Rolling update

Please see the @ref:[rolling update procedure from Java serialization to Jackson](../additional/rolling-updates.md#from-java-serialization-to-jackson).

### Java serialization in consistent hashing

When using a consistent hashing router keys that were not bytes or a String are serialized.
You might have to add a serializer for you hash keys, unless one of the default serializer are not
handling that type and it was previously "accidentally" serialized with Java serialization.

## Configuration and behavior changes

The following documents configuration changes and behavior changes where no action is required. In some cases the old
behavior can be restored via configuration.

### Remoting dependencies have been made optional

Classic remoting depends on Netty and Artery UDP depends on Aeron. These are now both optional dependencies that need
to be explicitly added. See @ref[classic remoting](../remoting.md) or @ref[artery remoting](../remoting-artery.md) for instructions.

### Remote watch and deployment have been disabled without Cluster use

By default, these remoting features are disabled when not using Akka Cluster:

* Remote Deployment: falls back to creating a local actor
* Remote Watch: ignores the watch and unwatch request, and `Terminated` will not be delivered when the remote actor is stopped or if a remote node crashes
 
Watching an actor on a node outside the cluster may have unexpected
@ref[consequences](../remoting-artery.md#quarantine), such as quarantining
so it has been disabled by default in Akka 2.6.x This is the case if either
cluster is not used at all (only plain remoting) or when watching an actor outside of the cluster.

On the other hand, failure detection between nodes of the same cluster
do not have that shortcoming. Thus, when remote watching or deployment is used within
the same cluster, they are working the same in 2.6.x as before, except that a remote watch attempt before a node has joined 
will log a warning and be ignored, it must be done after the node has joined.

To optionally enable a watch without Akka Cluster or across a Cluster boundary between Cluster and non Cluster, 
knowing the consequences, all watchers (cluster as well as remote) need to set:
```
akka.remote.use-unsafe-remote-features-outside-cluster = on`.
```

When enabled

* An initial warning is logged on startup of `RemoteActorRefProvider`
* A warning will be logged on remote watch attempts, which you can suppress by setting
```
akka.remote.warn-unsafe-watch-outside-cluster = off
```

### Schedule periodically with fixed-delay vs. fixed-rate

The `Scheduler.schedule` method has been deprecated in favor of selecting `scheduleWithFixedDelay` or
`scheduleAtFixedRate`.

The @ref:[Scheduler](../scheduler.md#schedule-periodically) documentation describes the difference between
`fixed-delay` and `fixed-rate` scheduling. If you are uncertain of which one to use you should pick
`startTimerWithFixedDelay`.

The deprecated `schedule` method had the same semantics as `scheduleAtFixedRate`, but since that can result in
bursts of scheduled tasks or messages after long garbage collection pauses and in worst case cause undesired
load on the system `scheduleWithFixedDelay` is often preferred.

For the same reason the following methods have also been deprecated:

* `TimerScheduler.startPeriodicTimer`, replaced by `startTimerWithFixedDelay` or `startTimerAtFixedRate`
* `FSM.setTimer`, replaced by `startSingleTimer`, `startTimerWithFixedDelay` or `startTimerAtFixedRate`
* `PersistentFSM.setTimer`, replaced by `startSingleTimer`, `startTimerWithFixedDelay` or `startTimerAtFixedRate`

### Internal dispatcher introduced

To protect the Akka internals against starvation when user code blocks the default dispatcher (for example by accidental
use of blocking APIs from actors) a new internal dispatcher has been added. All of Akka's internal, non-blocking actors
now run on the internal dispatcher by default.

The dispatcher can be configured through `akka.actor.internal-dispatcher`.

For maximum performance, you might want to use a single shared dispatcher for all non-blocking,
asynchronous actors, user actors and Akka internal actors. In that case, can configure the
`akka.actor.internal-dispatcher` with a string value of `akka.actor.default-dispatcher`.
This reinstantiates the behavior from previous Akka versions but also removes the isolation between
user and Akka internals. So, use at your own risk!

Several `use-dispatcher` configuration settings that previously accepted an empty value to fall back to the default
dispatcher has now gotten an explicit value of `akka.actor.internal-dispatcher` and no longer accept an empty
string as value. If such an empty value is used in your `application.conf` the same result is achieved by simply removing
that entry completely and having the default apply.

For more details about configuring dispatchers, see the @ref[Dispatchers](../dispatchers.md)

### Default dispatcher size

Previously the factor for the default dispatcher was set a bit high (`3.0`) to give some extra threads in case of accidental
blocking and protect a bit against starving the internal actors. Since the internal actors are now on a separate dispatcher
the default dispatcher has been adjusted down to `1.0` which means the number of threads will be one per core, but at least
`8` and at most `64`. This can be tuned using the individual settings in `akka.actor.default-dispatcher.fork-join-executor`.

### Mixed version

Startup will fail if mixed versions of a product family (such as Akka) are accidentally used. This was previously
only logged as a warning. There is no guarantee mixed modules will work and it's better to fail early than
that the application is crashing at a later time than startup. 

### Cluster Sharding

#### waiting-for-state-timeout reduced to 2s

This has been reduced to speed up ShardCoordinator initialization in smaller clusters.
The read from ddata is a ReadMajority. For small clusters (< majority-min-cap) every node needs to respond
so it is more likely to timeout if there are nodes restarting, for example when there is a rolling re-deploy happening.

#### Passivate idle entity

The configuration `akka.cluster.sharding.passivate-idle-entity-after` is now enabled by default.
Sharding will passivate entities when they have not received any messages after this duration.
To disable passivation you can use configuration:

```
akka.cluster.sharding.passivate-idle-entity-after = off
```

It is always disabled if @ref:[Remembering Entities](../cluster-sharding.md#remembering-entities) is enabled.

#### Cluster Sharding stats

A new field has been added to the response of a `ShardRegion.GetClusterShardingStats` command
for any shards per region that may have failed or not responded within the new configurable `akka.cluster.sharding.shard-region-query-timeout`. 
This is described further in @ref:[inspecting sharding state](../cluster-sharding.md#inspecting-cluster-sharding-state).

### Distributed Data

#### Config for message payload size

Configuration properties for controlling sizes of `Gossip` and `DeltaPropagation` messages in Distributed Data
have been reduced. Previous defaults sometimes resulted in messages exceeding max payload size for remote
actor messages.

The new configuration properties are:

```
akka.cluster.distributed-data.max-delta-elements = 500
akka.cluster.distributed-data.delta-crdt.max-delta-size = 50
```

#### DataDeleted

`DataDeleted` has been changed in its usage. While it is still a possible response to a Delete request,
it is no longer the response when an `Update` or `Get` request couldn't be performed because the entry has been deleted.
In its place are two new possible responses to a request, `UpdateDataDeleted` for an `Update` and `GetDataDeleted`
for a `Get`.

The reason for this change is that `DataDeleted` didn't extend the `UpdateResponse` and `GetResponse` types
and could therefore cause problems when `Update` and `Get` were used with `ask`. This was also a problem for
Akka Typed.

### CoordinatedShutdown is run from ActorSystem.terminate

No migration is needed but it is mentioned here because it is a change in behavior.

When `ActorSystem.terminate()` is called, @ref:[`CoordinatedShutdown`](../coordinated-shutdown.md)
will be run in Akka 2.6.x, which wasn't the case in 2.5.x. For example, if using Akka Cluster this means that
member will attempt to leave the cluster gracefully.

If this is not desired behavior, for example in tests, you can disable this feature with the following configuration
and then it will behave as in Akka 2.5.x:

```
akka.coordinated-shutdown.run-by-actor-system-terminate = off
```

### Scheduler not running tasks when shutdown

When the `ActorSystem` was shutting down and the `Scheduler` was closed all outstanding scheduled tasks were run,
which was needed for some internals in Akka but a surprising behavior for end users. Therefore this behavior has
changed in Akka 2.6.x and outstanding tasks are not run when the system is terminated.

Instead, `system.registerOnTermination` or `CoordinatedShutdown` can be used for running such tasks when the shutting
down.

### IOSources & FileIO

`FileIO.toPath`, `StreamConverters.fromInputStream`, and `StreamConverters.fromOutputStream` now always fail the materialized value in case of failure. 
It is no longer required to both check the materialized value and the `Try[Done]` inside the @apidoc[IOResult]. In case of an IO failure
the exception will be @apidoc[IOOperationIncompleteException] instead of @apidoc[AbruptIOTerminationException].

Additionally when downstream of the IO-sources cancels with a failure, the materialized value
is failed with that failure rather than completed successfully.

### Akka now uses Fork Join Pool from JDK

Previously, Akka contained a shaded copy of the ForkJoinPool. In benchmarks, we could not find significant benefits of
keeping our own copy, so from Akka 2.6.0 on, the default FJP from the JDK will be used. The Akka FJP copy was removed.

### Logging of dead letters

When the number of dead letters have reached configured `akka.log-dead-letters` value it didn't log
more dead letters in Akka 2.5.x. In Akka 2.6.x the count is reset after configured `akka.log-dead-letters-suspend-duration`.

`akka.log-dead-letters-during-shutdown` default configuration changed from `on` to `off`.

### Cluster failure detection

Default number of nodes that each node is observing for failure detection has increased from 5 to 9.
The reason is to have better coverage and unreachability information for downing decisions.

Configuration property:

```
akka.cluster.monitored-by-nr-of-members = 9
```

### TestKit

`expectNoMessage()` without timeout parameter is now using a new configuration property
`akka.test.expect-no-message-default` (short timeout) instead of `remainingOrDefault` (long timeout).

### Config library resolution change

The [Lightbend Config Library](https://github.com/lightbend/config) has been updated to load both `reference.conf`
and user config files such as `application.conf` before substitution of variables used in the `reference.conf`. 
This makes it possible to override such variables in `reference.conf` with user configuration.

For example, the default config for Cluster Sharding, refers to the default config for Distributed Data, in 
`reference.conf` like this:

```ruby
akka.cluster.sharding.distributed-data = ${akka.cluster.distributed-data}
``` 

In Akka 2.5.x this meant that to override default gossip interval for both direct use of Distributed Data and Cluster Sharding
in the same application you would have to change two settings:

```ruby
akka.cluster.distributed-data.gossip-interval = 3s
akka.cluster.sharding.distributed-data = 3s
```

In Akka 2.6.0 and forward, changing the default in the `akka.cluster.distributed-data` config block will be done before
the variable in `reference.conf` is resolved, so that the same change only needs to be done once:

```ruby
akka.cluster.distributed-data.gossip-interval = 3s
```

The following default settings in Akka are using such substitution and may be affected if you are changing the right
hand config path in your `application.conf`:

```ruby
akka.cluster.sharding.coordinator-singleton = ${akka.cluster.singleton}
akka.cluster.sharding.distributed-data = ${akka.cluster.distributed-data}
akka.cluster.singleton-proxy.singleton-name = ${akka.cluster.singleton.singleton-name}
akka.cluster.typed.receptionist.distributed-data = ${akka.cluster.distributed-data}
akka.remote.classic.netty.ssl = ${akka.remote.classic.netty.tcp}
akka.remote.artery.advanced.materializer = ${akka.stream.materializer}
``` 


## Source incompatibilities

### StreamRefs

The materialized value for `StreamRefs.sinkRef` and `StreamRefs.sourceRef` is no longer wrapped in
`Future`/`CompletionStage`. It can be sent as reply to `sender()` immediately without using the `pipe` pattern.

`StreamRefs` was marked as @ref:[may change](../common/may-change.md).

## Akka Typed

### Naming convention changed

In needing a way to distinguish the new APIs in code and docs from the original, Akka used the naming
convention `untyped`. All references of the original have now been changed to `classic`. The
reference of the new APIs as `typed` is going away as it becomes the primary APIs.

### Receptionist has moved

The receptionist had a name clash with the default Cluster Client Receptionist at `/system/receptionist` and will now 
instead either run under `/system/localReceptionist` or `/system/clusterReceptionist`.

The path change means that the receptionist information will not be disseminated between 2.5.x and 2.6.x nodes during a
rolling update from 2.5.x to 2.6.x if you use Akka Typed. See @ref:[rolling updates with typed Receptionist](../additional/rolling-updates.md#akka-typed-with-receptionist-or-cluster-receptionist)

### Cluster Receptionist using own Distributed Data

In 2.5.x the Cluster Receptionist was using the shared Distributed Data extension but that could result in
undesired configuration changes if the application was also using that and changed for example the `role`
configuration.

In 2.6.x the Cluster Receptionist is using it's own independent instance of Distributed Data.

This means that the receptionist information will not be disseminated between 2.5.x and 2.6.x nodes during a
rolling update from 2.5.x to 2.6.x if you use Akka Typed. See @ref:[rolling updates with typed Cluster Receptionist](../additional/rolling-updates.md#akka-typed-with-receptionist-or-cluster-receptionist)

### Akka Typed API changes

Akka Typed APIs were still marked as @ref:[may change](../common/may-change.md) in Akka 2.5.x and a few changes were
made before finalizing the APIs. Compared to Akka 2.5.x the source incompatible changes are:

* `Behaviors.intercept` now takes a factory function for the interceptor.
* `ActorSystem.scheduler` previously gave access to the classic `akka.actor.Scheduler` but now returns a specific `akka.actor.typed.Scheduler`.
  Additionally `schedule` method has been replaced by `scheduleWithFixedDelay` and `scheduleAtFixedRate`. Actors that needs to schedule tasks should
  prefer `Behaviors.withTimers`.
* `TimerScheduler.startPeriodicTimer`, replaced by `startTimerWithFixedDelay` or `startTimerAtFixedRate`
* `Routers.pool` now take a factory function rather than a `Behavior` to protect against accidentally sharing same behavior instance and state across routees.
* The `request` parameter in Distributed Data commands was removed, in favor of using `ask` with the new `ReplicatorMessageAdapter`.
* Removed `Behavior.same`, `Behavior.unhandled`, `Behavior.stopped`, `Behavior.empty`, and `Behavior.ignore` since
  they were redundant with corresponding @scala[scaladsl.Behaviors.x]@java[javadsl.Behaviors.x].
* `ActorContext` parameter removed in `javadsl.ReceiveBuilder` for the functional style in Java. Use `Behaviors.setup`
   to retrieve `ActorContext`, and use an enclosing class to hold initialization parameters and `ActorContext`.
* Java @javadoc[EntityRef](akka.cluster.sharding.typed.javadsl.EntityRef) ask timeout now takes a `java.time.Duration` rather than a @apidoc[Timeout]
* Changed method signature for `EventAdapter.fromJournal` and support for `manifest` in `EventAdapter`.
* Renamed @scala[`widen`]@java[`Behaviors.widen`] to @scala[`transformMessages`]@java[`Behaviors.transformMessages`]
* `BehaviorInterceptor`, `Behaviors.monitor`, `Behaviors.withMdc` and @scala[`transformMessages`]@java[`Behaviors.transformMessages`] takes
  a @scala[`ClassTag` parameter (probably source compatible)]@java[`interceptMessageClass` parameter].
  `interceptMessageType` method in `BehaviorInterceptor` is replaced with this @scala[`ClassTag`]@java[`Class`] parameter.
* `Behavior.orElse` has been removed because it wasn't safe together with `narrow`.
* `StashBuffer`s are now created with `Behaviors.withStash` rather than instantiating directly
* To align with the Akka Typed style guide `SpawnProtocol` is now created through @scala[`SpawnProtocol()`]@java[`SpawnProtocol.create()`], the special `Spawn` message
  factories has been removed and the top level of the actor protocol is now `SpawnProtocol.Command`
* `Future` removed from `ActorSystem.systemActorOf`.
* `toUntyped` has been renamed to `toClassic`.
* Akka Typed is now using SLF4J as the logging API. @scala[`ActorContext.log`]@java[`ActorContext.getLog`] returns
  an `org.slf4j.Logger`. MDC has been changed to only support `String` values.
* `setLoggerClass` in `ActorContext` has been renamed to `setLoggerName`.
* `GetDataDeleted` and `UpdateDataDeleted` introduced as described in @ref[DataDeleted](#datadeleted).
* `SubscribeResponse` introduced in `Subscribe` because the responses can be both `Changed` and `Deleted`.
* `ReplicationDeleteFailure` renamed to `DeleteFailure`.
* `EventSourcedEntity` removed in favor using plain `EventSourcedBehavior` because the alternative way was
  causing more confusion than adding value. Construction of `PersistentId` for the `EventSourcedBehavior` is
  facilitated by factory methods in `PersistenceId`.
* `PersistenceId.apply(String)` renamed to `PersistenceId.ofUniqueId(String)`  
* `akka.cluster.sharding.typed.scaladsl.Entity.apply` changed to use two parameter lists because the new
  `EntityContext.entityTypeKey` required additional type parameter that is inferred better with a secondary
  parameter list.
* `EventSourcedBehavior.withEnforcedReplies` signature changed. Command is not required to extend `ExpectingReply`
  anymore. `ExpectingReply` has therefore been removed.
* `ActorContext` is now a mandatory constructor parameter in `AbstractBehavior`. Create via `Behaviors.setup.
  The reason is to encourage right usage and detect mistakes like not creating a new instance (via `setup`)
  when the behavior is supervised and restarted.    
* `LoggingEventFilter` has been renamed to `LoggingTestKit` and its `intercept` method renamed to `assert`
* Scala `ask` from `AskPattern` now implicitly converts an implicit `ActorSystem[_]` to `Scheduler` to eliminate some boilerplate.

#### Akka Typed Stream API changes

* `ActorSource.actorRef` relying on `PartialFunction` has been replaced in the Java API with a variant more suitable to be called by Java.
* Factories for creating a materializer from an `akka.actor.typed.ActorSystem` have been removed.
  A stream can be run with an `akka.actor.typed.ActorSystem` @scala[in implicit scope]@java[parameter]
  and therefore the need for creating a materializer has been reduced.
* `actorRefWithAck` has been renamed to `actorRefWithBackpressure`

## Akka Stream changes

### Materializer changes

A default materializer is now provided out of the box. For the Java API just pass `system` when running streams,
for Scala an implicit materializer is provided if there is an implicit `ActorSystem` available. This avoids leaking
materializers and simplifies most stream use cases somewhat.

The `ActorMaterializer` factories has been deprecated and replaced with a few corresponding factories in `akka.stream.Materializer`.
New factories with per-materializer settings has not been provided but should instead be done globally through config or per stream,
see below for more details.

Having a default materializer available means that most, if not all, usages of Java `ActorMaterializer.create()`
and Scala `implicit val materializer = ActorMaterializer()` should be removed.

Details about the stream materializer can be found in @ref:[Actor Materializer Lifecycle](../stream/stream-flows-and-basics.md#actor-materializer-lifecycle)

When using streams from typed the same factories and methods for creating materializers and running streams as from classic can now be used with typed. The
`akka.stream.typed.scaladsl.ActorMaterializer` and `akka.stream.typed.javadsl.ActorMaterializerFactory` that previously existed in the `akka-stream-typed` module has been removed.

### Materializer settings deprecated

The `ActorMaterializerSettings` class has been deprecated.

All materializer settings are available as configuration to change the system default or through attributes that can be
used for individual streams when they are materialized.

| MaterializerSettings   | Corresponding attribute                           | Config  |
-------------------------|---------------------------------------------------|---------|
| `initialInputBufferSize`        | `Attributes.inputBuffer(initial, max)`   | `akka.stream.materializer.initial-input-buffer-size` |
| `maxInputBufferSize`            | `Attributes.inputBuffer(initial, max)`   | `akka.stream.materializer.max-input-buffer-size` |
| `dispatcher`                    | `ActorAttributes.dispatcher(name)`       | `akka.stream.materializer.dispatcher` |
| `supervisionDecider`            | `ActorAttributes.supervisionStrategy`    | na |
| `debugLogging`                  | `ActorAttributes.debugLogging`           | `akka.stream.materializer.debug-logging` |
| `outputBurstLimit`              | `ActorAttributes.outputBurstLimit`       | `akka.stream.materializer.output-burst-limit` |
| `fuzzingMode`                   | `ActorAttributes.fuzzingMode`            | `akka.stream.materializer.debug.fuzzing-mode` |
| `autoFusing`                    | no longer used (since 2.5.0)             | na |
| `maxFixedBufferSize`            | `ActorAttributes.maxFixedBufferSize`     | `akka.stream.materializer.max-fixed-buffer-size` |
| `syncProcessingLimit`           | `ActorAttributes.syncProcessingLimit`    | `akka.stream.materializer.sync-processing-limit` |
| `IOSettings.tcpWriteBufferSize` | `Tcp.writeBufferSize`                    | `akka.stream.materializer.io.tcp.write-buffer-size` |
| `blockingIoDispatcher`          | na                                       | `akka.stream.materializer.blocking-io-dispatcher` |


| StreamRefSettings                | Corresponding StreamRefAttributes | Config  |
-----------------------------------|-----------------------------------|---------|
| `bufferCapacity`                 | `bufferCapacity`                  | `akka.stream.materializer.stream-ref.buffer-capacity` |
| `demandRedeliveryInterval`       | `demandRedeliveryInterval`        | `akka.stream.materializer.stream-ref.demand-redelivery-interval` |
| `subscriptionTimeout`            | `subscriptionTimeout`             | `akka.stream.materializer.stream-ref.subscription-timeout` |
| `finalTerminationSignalDeadline` | `finalTerminationSignalDeadline`  | `akka.stream.materializer.stream-ref.final-termination-signal-deadline` |


| SubscriptionTimeoutSettings      | Corresponding ActorAttributes               | Config  |
-----------------------------------|---------------------------------------------|---------|
| `subscriptionTimeoutSettings.mode`           | `streamSubscriptionTimeoutMode` | `akka.stream.materializer.subscription-timeout.mode` |
| `subscriptionTimeoutSettings.timeout`        | `streamSubscriptionTimeout`     | `akka.stream.materializer.subscription-timeout.timeout` |

Setting attributes on individual streams can be done like so:

Scala
:  @@snip [StreamAttributeDocSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/StreamAttributeDocSpec.scala) { #attributes-on-stream }

Java
:  @@snip [StreamAttributeDocTest.java](/akka-stream-tests/src/test/java/akka/stream/StreamAttributeDocTest.java) { #attributes-on-stream }

### Stream cancellation available upstream

Previously an Akka streams stage or operator failed it was impossible to discern this from
the stage just cancelling. This has been improved so that when a stream stage fails the cause
will be propagated upstream.

The following operators have a slight change in behavior because of this:

* `FileIO.fromPath`, `FileIO.fromFile` and `StreamConverters.fromInputStream`  will fail the materialized future with
  an `IOOperationIncompleteException` when downstream fails
* `.watchTermination` will fail the materialized `Future` or `CompletionStage` rather than completing it when downstream fails
* `StreamRef` - `SourceRef` will cancel with a failure when the receiving node is downed

This also means that custom `GraphStage` implementations should be changed to pass on the
cancellation cause when downstream cancels by implementing the `OutHandler.onDownstreamFinish` signature
taking a `cause` parameter and calling `cancelStage(cause)` to pass the cause upstream. The old zero-argument
`onDownstreamFinish` method has been deprecated.


### Lazy and async stream operator changes

The operators that provide support for lazy and @scala[`Future`]@java[`CompletionStage`] stream construction were revised
to be more consistent.

The materialized value is now no longer wrapped in an @scala[`Option`]@java[`Optional`], instead the @scala[`Future`]@java[`CompletionStage`]
is failed with a `akka.stream.NeverMaterializedException` in the cases that would previously lead to @scala[`None`]@java[an empty `Optional`] 

A deferred creation of the stream based on the initial element like how the deprecated `lazyInit` worked can be achieved by combining 
@scala[`future(Flow|Sink)`] @java[`completionStage(Flow|Sink)`] with `prefixAndTail`. See example in @scala[@ref:[futureFlow](../stream/operators/Flow/futureFlow.md)]
@java[@ref:[completionStageFlow](../stream/operators/Flow/completionStageFlow.md)]. 

#### javadsl.Flow 
  
| old                     | new |
------------------------|----------------
| lazyInit                | @ref:[lazyCompletionStageFlow](../stream/operators/Flow/lazyCompletionStageFlow.md) in combination with `prefixAndTail(1)` |
| lazyInitAsync           | @ref:[lazyCompletionStageFlow](../stream/operators/Flow/lazyCompletionStageFlow.md)  | 
|                         | @ref:[completionStageFlow](../stream/operators/Flow/completionStageFlow.md) |
|                          | @ref:[lazyFlow](../stream/operators/Flow/lazyFlow.md) |

### javadsl.Sink            
  
| old                     | new |
------------------------|----------------
| lazyInit                | @ref:[lazyCompletionStageSink](../stream/operators/Sink/lazyCompletionStageSink.md) in combination with `Flow.prefixAndTail(1)` |
| lazyInitAsync           | @ref:[lazyCompletionStageSink](../stream/operators/Sink/lazyCompletionStageSink.md) |
|                          | @ref:[completionStageSink](../stream/operators/Sink/completionStageSink.md) |
|                          | @ref:[lazySink](../stream/operators/Sink/lazySink.md) |
  
### javadsl.Source
  
| old                       | new |
--------------------------|----------------
| fromFuture                | @ref:[future](../stream/operators/Source/future.md) |
| fromCompletionStage       | @ref:[completionStage](../stream/operators/Source/completionStage.md) |
| fromFutureSource          | @ref:[futureSource](../stream/operators/Source/futureSource.md) |
| fromSourceCompletionStage | @ref:[completionStageSource](../stream/operators/Source/completionStageSource.md) |
| lazily                    | @ref:[lazySource](../stream/operators/Source/lazySource.md) |
| lazilyAsync               | @ref:[lazyCompletionStage](../stream/operators/Source/lazyCompletionStage.md) |
|                            | @ref:[lazySingle](../stream/operators/Source/lazySingle.md) |
|                            | @ref:[lazyCompletionStageSource](../stream/operators/Source/lazyCompletionStageSource.md) |
    
### scaladsl.Flow

| old                     | new |
--------------------------|----------------
| lazyInit                | @ref:[lazyFutureFlow](../stream/operators/Flow/lazyFutureFlow.md) |
| lazyInitAsync           | @ref:[lazyFutureFlow](../stream/operators/Flow/lazyFutureFlow.md) |
|                         | @ref:[futureFlow](../stream/operators/Flow/futureFlow.md) |
|                         | @ref:[lazyFlow](../stream/operators/Flow/lazyFlow.md) |

### scaladsl.Sink            

| old                     | new |
------------------------|----------------
| lazyInit                | @ref:[lazyFutureSink](../stream/operators/Sink/lazyFutureSink.md) in combination with `Flow.prefixAndTail(1)` |
| lazyInitAsync           | @ref:[lazyFutureSink](../stream/operators/Sink/lazyFutureSink.md) |
|                         | @ref:[futureSink](../stream/operators/Sink/futureSink.md) |
|                         | @ref:[lazySink](../stream/operators/Sink/lazySink.md) |

### scaladsl.Source

| old                       | new |
--------------------------|----------------
| fromFuture                | @ref:[future](../stream/operators/Source/future.md) |
| fromCompletionStage       | @ref:[completionStage](../stream/operators/Source/completionStage.md) |
| fromFutureSource          | @ref:[futureSource](../stream/operators/Source/futureSource.md) |
| fromSourceCompletionStage |   |
| lazily                    | @ref:[lazySource](../stream/operators/Source/lazySource.md) |
| lazilyAsync               | @ref:[lazyFuture](../stream/operators/Source/lazyFuture.md) |
|                           | @ref:[lazySingle](../stream/operators/Source/lazySingle.md) |
|                           | @ref:[lazyFutureSource](../stream/operators/Source/lazyFutureSource.md) |
