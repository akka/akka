# Migration Guide 2.5.x to 2.6.x

It is now recommended to use @apidoc[akka.util.ByteString]`.emptyByteString()` instead of
@apidoc[akka.util.ByteString]`.empty()` when using Java because @apidoc[akka.util.ByteString]`.empty()`
is [no longer available as a static method](https://github.com/scala/bug/issues/11509) in the artifacts built for Scala 2.13.

## Scala 2.11 no longer supported

If you are still using Scala 2.11 then you must upgrade to 2.12 or 2.13

## Removed features that were deprecated

### akka-camel removed

After being deprecated in 2.5.0, the akka-camel module has been removed in 2.6.
As an alternative we recommend [Alpakka](https://doc.akka.io/docs/alpakka/current/).

This is of course not a drop-in replacement. If there is community interest we
are open to setting up akka-camel as a separate community-maintained
repository.

### akka-agent removed

After being deprecated in 2.5.0, the akka-agent module has been removed in 2.6.
If there is interest it may be moved to a separate, community-maintained
repository.

### akka-contrib removed

The akka-contrib module was deprecated in 2.5 and has been removed in 2.6.
To migrate, take the components you are using from [Akka 2.5](https://github.com/akka/akka/tree/release-2.5/akka-contrib)
and include them in your own project or library under your own package name.

### Actor DSL removed

Actor DSL is a rarely used feature and has been deprecated since `2.5.0`.
Use plain `system.actorOf` instead of the DSL to create Actors if you have been using it.

### Timing operator removed

`akka.stream.extra.Timing` has been removed. If you need it you can now find it in `akka.stream.contrib.Timed` from
 [Akka Stream Contrib](https://github.com/akka/akka-stream-contrib/blob/master/src/main/scala/akka/stream/contrib/Timed.scala).

### actorFor removed

`actorFor` has been deprecated since `2.2`. Use `ActorSelection` instead.

### Netty UDP removed

Classic remoting over UDP has been deprecated since `2.5.0` and now has been removed.
To continue to use UDP configure @ref[Artery UDP](../remoting-artery.md#configuring-ssl-tls-for-akka-remoting) or migrate to Artery TCP.
A full cluster restart is required to change to Artery.

### Untyped actor removed

`UntypedActor` has been depcated since `2.5.0`. Use `AbstractActor` instead.

### UntypedPersistentActor removed

Use `AbstractPersistentActor` instead.

### UntypedPersistentActorWithAtLeastOnceDelivery removed

Use @apidoc[AbstractPersistentActorWithAtLeastOnceDelivery] instead.

### Various removed methods

* `Logging.getLogger(UntypedActor)` Untyped actor has been removed, use AbstractActor instead.
* `LoggingReceive.create(Receive, ActorContext)` use `AbstractActor.Receive` instead.
* `ActorMaterialzierSettings.withAutoFusing` disabling fusing is no longer possible.

### JavaTestKit removed

The `JavaTestKit` has been deprecated since `2.5.0`. Use `akka.testkit.javadsl.TestKit` instead.

## Deprecated features

### TypedActor

`akka.actor.TypedActor` has been deprecated as of 2.6 in favor of the
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


@@ Remoting

### Default remoting is now Artery TCP

@ref[Artery TCP](../remoting-artery.md) is now the default remoting implementation.
Classic remoting has been deprecated and will be removed in `2.7.0`.


<a id="classic-to-artery"></a>
#### Migrating from classic remoting to Artery

Artery has the same functionality as classic remoting and you should normally only have to change the
configuration to switch.
To switch a full cluster restart is required and any overrides for classic remoting need to be ported to Artery configuration.

Artery defaults to TCP (see @ref:[selected transport](#selecting-a-transport)) which is a good start
when migrating from classic remoting.

The protocol part in the Akka `Address`, for example `"akka.tcp://actorSystemName@10.0.0.1:2552/user/actorName"`
has changed from `akka.tcp` to `akka`. If you have configured or hardcoded any such addresses you have to change
them to `"akka://actorSystemName@10.0.0.1:2552/user/actorName"`. `akka` is used also when TLS is enabled.
One typical place where such address is used is in the `seed-nodes` configuration.

The configuration is different, so you might have to revisit any custom configuration. See the full
@ref:[reference configuration for Artery](../general/configuration.md#config-akka-remote-artery) and
@ref:[reference configuration for classic remoting](../general/configuration.md#config-akka-remote).

Configuration that is likely required to be ported:

* `akka.remote.netty.tcp.hostname` => `akka.remote.artery.canonical.hostname`
* `akka.remote.netty.tcp.port`=> `akka.remote.artery.canonical.port`

One thing to be aware of is that rolling update from classic remoting to Artery is not supported since the protocol
is completely different. It will require a full cluster shutdown and new startup.

If using SSL then `tcp-tls` needs to be enabled and setup. See @ref[Artery docs for SSL](../remoting-artery.md#configuring-ssl-tls-for-akka-remoting)
for how to do this.


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

Classic remoting is deprecated but can be used in `2.6.` Explicitly disable Artery by setting property `akka.remote.artery.enabled` to `false`. Further, any configuration under `akka.remote` that is
specific to classic remoting needs to be moved to `akka.remote.classic`. To see which configuration options
are specific to classic search for them in: [`akka-remote/reference.conf`](/akka-remote/src/main/resources/reference.conf)


## Configuration and behavior changes

The following documents configuration changes and behavior changes where no action is required. In some cases the old
behavior can be restored via configuration.

### Remoting dependencies have been made optional

Classic remoting depends on Netty and Artery UDP depends on Aeron. These are now both optional dependencies that need
to be explicitly added. See @ref[classic remoting](../remoting.md) or [artery remoting](../remoting-artery.md) for instructions.

## Schedule periodically with fixed-delay vs. fixed-rate

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

### Cluster sharding

#### waiting-for-state-timeout reduced to 2s

This has been reduced to speed up ShardCoordinator initialization in smaller clusters.
The read from ddata is a ReadMajority, for small clusters (< majority-min-cap) every node needs to respond
so is more likely to timeout if there are nodes restarting e.g. when there is a rolling re-deploy happening.

### Passivate idle entity

The configuration `akka.cluster.sharding.passivate-idle-entity-after` is now enabled by default.
Sharding will passivate entities when they have not received any messages after this duration.
To disable passivation you can use configuration:

```
akka.cluster.sharding.passivate-idle-entity-after = off
```

### CoordinatedShutdown is run from ActorSystem.terminate

No migration is needed but it is mentioned here because it is a change in behavior.

When `ActorSystem.terminate()` is called, @ref:[`CoordinatedShutdown`](../actors.md#coordinated-shutdown)
will be run in Akka 2.6.x, which wasn't the case in 2.5.x. For example, if using Akka Cluster this means that
member will attempt to leave the cluster gracefully.

If this is not desired behavior, for example in tests, you can disable this feature with the following configuration
and then it will behave as in Akka 2.5.x:

```
akka.coordinated-shutdown.run-by-actor-system-terminate = off
```


### Akka now uses Fork Join Pool from JDK

Previously, Akka contained a shaded copy of the ForkJoinPool. In benchmarks, we could not find significant benefits of
keeping our own copy, so from Akka 2.6 on, the default FJP from the JDK will be used. The Akka FJP copy was removed.

### Logging of dead letters

When the number of dead letters have reached configured `akka.log-dead-letters` value it didn't log
more dead letters in Akka 2.5. In Akka 2.6 the count is reset after configured `akka.log-dead-letters-suspend-duration`.

`akka.log-dead-letters-during-shutdown` default configuration changed from `on` to `off`.

## Source incompatibilities

### StreamRefs

The materialized value for `StreamRefs.sinkRef` and `StreamRefs.sourceRef` is no longer wrapped in
`Future`/`CompletionStage`. It can be sent as reply to `sender()` immediately without using the `pipe` pattern.

`StreamRefs` was marked as [may change](../common/may-change.md).

## Akka Typed

### Receptionist has moved

The receptionist had a name clash with the default Cluster Client Receptionist at `/system/receptionist` and will now 
instead either run under `/system/localReceptionist` or `/system/clusterReceptionist`.

The path change means that the receptionist information will not be disseminated between 2.5 and 2.6 nodes during a
rolling update from 2.5 to 2.6 if you use Akka Typed. When all old nodes have been shutdown
it will work properly again.

### Cluster Receptionist using own Distributed Data

In 2.5 the Cluster Receptionist was using the shared Distributed Data extension but that could result in
undesired configuration changes if the application was also using that and changed for example the `role`
configuration.

In 2.6 the Cluster Receptionist is using it's own independent instance of Distributed Data.

This means that the receptionist information will not be disseminated between 2.5 and 2.6 nodes during a
rolling update from 2.5 to 2.6 if you use Akka Typed. When all old nodes have been shutdown
it will work properly again.

### Akka Typed API changes

Akka Typed APIs are still marked as [may change](../common/may-change.md) and a few changes were
made before finalizing the APIs. Compared to Akka 2.5.x the source incompatible changes are:

* `Behaviors.intercept` now takes a factory function for the interceptor.
* Factory method `Entity.ofPersistentEntity` is renamed to `Entity.ofEventSourcedEntity` in the Java API for Akka Cluster Sharding Typed.
* New abstract class `EventSourcedEntityWithEnforcedReplies` in Java API for Akka Cluster Sharding Typed and corresponding factory method `Entity.ofEventSourcedEntityWithEnforcedReplies` to ease the creation of `EventSourcedBehavior` with enforced replies.
* New method `EventSourcedEntity.withEnforcedReplies` added to Scala API to ease the creation of `EventSourcedBehavior` with enforced replies.
* `ActorSystem.scheduler` previously gave access to the untyped `akka.actor.Scheduler` but now returns a typed specific `akka.actor.typed.Scheduler`.
  Additionally `schedule` method has been replaced by `scheduleWithFixedDelay` and `scheduleAtFixedRate`. Actors that needs to schedule tasks should
  prefer `Behaviors.withTimers`.
* `TimerScheduler.startPeriodicTimer`, replaced by `startTimerWithFixedDelay` or `startTimerAtFixedRate`
* `Routers.pool` now take a factory function rather than a `Behavior` to protect against accidentally sharing same behavior instance and state across routees.
* The `request` parameter in Distributed Data commands was removed, in favor of using `ask`.
* Removed `Behavior.same`, `Behavior.unhandled`, `Behavior.stopped`, `Behavior.empty`, and `Behavior.ignore` since
  they were redundant with corresponding @scala[scaladsl.Behaviors.x]@java[javadsl.Behaviors.x].
* `ActorContext` parameter removed in `javadsl.ReceiveBuilder` for the functional style in Java. Use `Behaviors.setup`
   to retrieve `ActorContext`, and use an enclosing class to hold initialization parameters and `ActorContext`.
* Java @apidoc[akka.cluster.sharding.typed.javadsl.EntityRef] ask timeout now takes a `java.time.Duration` rather than a @apidoc[Timeout]


#### Akka Typed Stream API changes

* `ActorSoruce.actorRef` relying on `PartialFunction` has been replaced in the Java API with a variant more suitable to be called by Java.

