# Migration Guide 2.4.x to 2.5.x

## Actor (Java)

### AbstractActor

`AbstractActor` has been promoted from its experimental/may change state and while doing this we
did some small, but important, improvements to the API that will require some mechanical
changes of your source code.

Previously the receive behavior was set with the `receive` method, but now an actor has
to define its initial receive behavior by implementing the `createReceive` method in
the `AbstractActor`. This has the advantages:

 * It gives a clear entry point of what to implement. The compiler tells you that the
abstract method must be implemented.
 * It's impossible to forget to set the receive behavior.
 * It's not possible to define the receive behavior more than once.

The return type of `createReceive` is `AbstractActor.Receive`. It defines which messages
your Actor can handle, along with the implementation of how the messages should be processed.
You can build such behavior with a builder named `ReceiveBuilder`.

`AbstractActor.Receive` can also be used in `getContext().become`.

The old `receive` method exposed Scala's `PartialFunction` and `BoxedUnit` in the signature,
which are unnecessary concepts for newcomers to learn. The new `createReceive` requires no
additional imports.

Note that The `Receive` can still be implemented in other ways than using the `ReceiveBuilder`
since it in the end is just a wrapper around a Scala `PartialFunction`. For example, one could
implement an adapter to [Javaslang Pattern Matching DSL](http://www.javaslang.io/javaslang-docs/#_pattern_matching).

The mechanical source code change for migration to the new `AbstractActor` is to implement the
`createReceive` instead of calling `receive` (compiler will tell that this is missing).

Old:

```
import akka.actor.AbstractActor;
import akka.japi.pf.ReceiveBuilder;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

public class SomeActor extends AbstractActor {
  public SomeActor() {
    receive(ReceiveBuilder
      .match(String.class, s -> System.out.println(s.toLowerCase())).
      .build());
  }
}
```

New:

```
import akka.actor.AbstractActor;

public class SomeActor extends AbstractActor {
  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(String.class, s -> System.out.println(s.toLowerCase()))
      .build();
  }
}
```

See @ref:[Receive messages](../actors.md#receive-messages) documentation for more advice about how to implement
`createReceive`.

A few new methods have been added with deprecation of the old. Worth noting is `preRestart`.

Old:

```
@Override
public void preRestart(Throwable reason, scala.Option<Object> message) {
  super.preRestart(reason, message);
}
```

New:

```
@Override
public void preRestart(Throwable reason, java.util.Optional<Object> message) {
  super.preRestart(reason, message);
}
```

### AbstractPersistentActor

Similar change as described above for `AbstractActor` is needed for `AbstractPersistentActor`. Implement `createReceiveRecover`
instead of `receiveRecover`, and `createReceive` instead of `receiveCommand`.

Old:

```
@Override
public PartialFunction<Object, BoxedUnit> receiveCommand() {
  return ReceiveBuilder.
    match(String.class, cmd -> {/* ... */}).build();
}

@Override
public PartialFunction<Object, BoxedUnit> receiveRecover() {
  return ReceiveBuilder.
      match(String.class, evt -> {/* ... */}).build();
}
```

New:

```
@Override
public Receive createReceive() {
  return receiveBuilder().
    match(String.class, cmd -> {/* ... */}).build();
}

@Override
public Receive createReceiveRecover() {
  return receiveBuilder().
      match(String.class, evt -> {/* ... */}).build();
}
```

### UntypedActor

`UntypedActor` has been deprecated in favor of `AbstractActor`. As a migration path you can extend
`UntypedAbstractActor` instead of `UntypedActor`.

Old:

```
import akka.actor.UntypedActor;

public class SomeActor extends UntypedActor {

  public static class Msg1 {}

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg instanceof Msg1) {
      Msg1 msg1 = (Msg1) msg;
      // actual work
    } else {
      unhandled(msg);
    }
  }
}
```

New:

```
import akka.actor.UntypedAbstractActor;

public class SomeActor extends UntypedAbstractActor {

  public static class Msg1 {}

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg instanceof Msg1) {
      Msg1 msg1 = (Msg1) msg;
      // actual work
    } else {
      unhandled(msg);
    }
  }
}
```

It's recommended to migrate `UntypedActor` to `AbstractActor` by implementing
`createReceive` instead of `onReceive`.

Old:

```
import akka.actor.UntypedActor;

public class SomeActor extends UntypedActor {

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg instanceof String) {
      String s = (String) msg;
      System.out.println(s.toLowerCase());
    } else {
      unhandled(msg);
    }
  }
}
```

New:

```
import akka.actor.AbstractActor;

public class SomeActor extends AbstractActor {
  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(String.class, s -> {
        System.out.println(s.toLowerCase());
      })
      .build();
  }
}
```

See @ref:[Receive messages](../actors.md#receive-messages) documentation for more advice about how to implement
`createReceive`.

Similar with `UntypedActorWithStash`, `UntypedPersistentActor`, and
`UntypedPersistentActorWithAtLeastOnceDelivery`.

## Actor (Scala)

### Actor DSL deprecation

Actor DSL is a rarely used feature and thus will be deprecated and removed.
Use plain `system.actorOf` instead of the DSL to create Actors if you have been using it.

### ExtensionKey Deprecation

`ExtensionKey` is a shortcut for writing @ref:[Akka Extensions](../extending-akka.md) but extensions created with it
cannot be used from Java and it does in fact not save many lines of code over directly implementing `ExtensionId`.

Old:

```
object MyExtension extends ExtensionKey[MyExtension]
```

New:

```
object MyExtension extends ExtensionId[MyExtension] with ExtensionIdProvider {

  override def lookup = MyExtension

  override def createExtension(system: ExtendedActorSystem): MyExtension =
    new MyExtension(system)

  // needed to get the type right when used from Java
  override def get(system: ActorSystem): MyExtension = super.get(system)
}
```

## Actor Mailbox

Scala 2.12 is using the standard JDK8 ForkJoinPool, which may cause performance regression for
some Actor messaging scenarios. Therefore we have embedded the ForkJoinPool from Scala 2.11 in
Akka. This breaks binary backwards compatibility for custom `Mailbox` implementations that were
compiled with Akka 2.4.

This is only a problem if you are using a library that provides a custom `Mailbox` implementation.
The change is source compatible and such library should be recompiled and released for Akka 2.5.

## Streams

### Removal of StatefulStage, PushPullStage

`StatefulStage` and `PushPullStage` were first introduced in Akka Streams 1.0, and later deprecated
and replaced by `GraphStage` in 2.0-M2. The `GraphStage` API has all features (and even more) as the
previous APIs and is even nicer to use.

Please refer to the @ref:[GraphStage documentation](../stream/stream-customize.md#graphstage), for details on building custom GraphStages.

`StatefulStage` would be migrated to a simple `GraphStage` that contains some mutable state in its `GraphStageLogic`,
and `PushPullStage` directly translate to graph stages.

### Removal of `Source.transform`, replaced by `via`

Along with the removal of `Stage` (as described above), the `transform` methods creating Flows/Sources/Sinks
from `Stage` have been removed. They are replaced by using `GraphStage` instances with `via`, e.g.:

```
exampleFlow.transform(() => new MyStage())
```

would now be:

```
myFlow.via(new MyGraphStage)
```

as the `GraphStage` itself is a factory of logic instances.

### SubFlow.zip and SubSource.zip now emit akka.japi.Pair instead of Scala's Pair

The the Java API's `zip` operator on `SubFlow` and `SubSource` has been emiting
Scala's `Pair` (`Tuple2`) instead of `akka.japi.Pair`. This is fixed in Akka 2.5 where it emits the proper
Java DSl type.

Please note that the `zip` operator on `Source` and `Flow` has had the correct type,
this change only affects the `Sub...` versions of those classes.

### Deprecation of ActorSubscriber and ActorPublisher

The classes `ActorPublisher` and `ActorSubscriber` were the first user-facing Reactive Streams integration
API that we provided for end-users. Akka Streams APIs have evolved and improved a lot since then, and now
there is no need to use these low-level abstractions anymore. It is easy to get things wrong when implementing them,
and one would have to validate each implementation of such Actor using the Reactive Streams Technology Compatibility Kit.

The replacement API is the powerful `GraphStage`. It has all features that raw Actors provided for implementing Stream
stages and adds additional protocol and type-safety. You can learn all about it in the documentation:
@ref:[Custom stream processing](../stream/stream-customize.md).

You should also read the blog post series on the official team blog, starting with [Mastering GraphStages, part I](http://blog.akka.io/streams/2016/07/30/mastering-graph-stage-part-1),
which explains using and implementing GraphStages in more practical terms than the reference documentation.

### Order of Attributes List

Imporant performance improvement could be achieved by reverting the order of the `attributesList` in `Attributes`.

The `attributeList` is ordered with the most specific attribute first, least specific last.
Note that the order was the opposite in Akka 2.4.x (but it was not really documented).

The semantics of the convenience methods, such as `get` and `getFirst` are the same, but if you use the `attributesList`
directly or via `filtered` or `getAttributeList` you need to take the new order into consideration.

### Dispatcher attribute

The `ActorAttributes.dispatcher` attribute is adding an async boundary in 2.5, since that is the typical desired behavior.
In 2.4 an explicit *async* marker (`AsyncBoundary` attribute) had to be added. For example, this means that `Source` that
defined `blocking-io-dispatcher` as default followed by a `map` will now be separated by an async boundary, which was not
the case in 2.4.

### Removal of the auto-fuse setting

In 2.4 fusing stages together into the same actor could be completely disabled with the setting
`akka.stream.materializer.auto-fusing`. The new materializer introduced in Akka 2.5 does not support disabling fusing,
so this setting does not have any effect any more and has been deprecated. Running each stage in a stream on a separate
actor can be done by adding explicit async boundaries around every stage. How to add asynchronous boundaries can be seen
in @ref:[Operator Fusion](../stream/stream-flows-and-basics.md#operator-fusion).

## Remote

<a id="mig25-mutual"></a>
### Mutual TLS authentication now required by default for netty-based SSL transport

Mutual TLS authentication is now required by default for the netty-based SSL transport.

Nodes that are configured with this setting to `on` might not be able to receive messages from nodes that run on older
versions of akka-remote. This is because in versions of Akka < 2.4.12 the active side of the remoting
connection will not send over certificates even if asked to.

It is still possible to make a rolling upgrade from a version < 2.4.12 by doing the upgrade stepwise:
:
 * first, upgrade Akka to the latest version but keep `akka.remote.netty.ssl.require-mutual-authentication` at `off`
and do a first rolling upgrade
 * second, turn the setting to `on` and do another rolling upgrade


For more information see the documentation for the `akka.remote.netty.ssl.require-mutual-authentication` configuration setting
in @ref:[akka-remote's reference.conf](../general/configuration.md#config-akka-remote).

<a id="mig25-addser"></a>
### additional-serialization-bindings

From Akka 2.5.0 the `additional-serialization-bindings` are enabled by default. That defines
serializers that are replacing some Java serialization that were used in 2.4. This setting was disabled
by default in Akka 2.4.16 but can also be enabled in an Akka 2.4 system.

To still be able to support rolling upgrade from a system with this setting disabled, e.g. default for 2.4.16,
it is possible to disable the additional serializers and continue using Java serialization for those messages.

```ruby
akka.actor {
  # Set this to off to disable serialization-bindings defined in
  # additional-serialization-bindings. That should only be needed
  # for backwards compatibility reasons.
  enable-additional-serialization-bindings = off
}
```

Please note that this setting must be the same on all nodes participating in a cluster, otherwise
the mis-aligned serialization configurations will cause deserialization errors on the receiving nodes.

After performing a rolling upgrade from 2.4 to 2.5 with `enable-additional-serialization-bindings = off`
you can perform another rolling upgrade and change this setting to `on`.
See also @ref:[Rolling upgrades](../serialization.md#rolling-upgrades).

### With serialize-messages the deserialized message is actually sent

The flag `akka.actor.serialize-messages = on` triggers serialization and deserialization of each message sent in the
`ActorSystem`. With this setting enabled the message actually passed on to the actor previously was the original
message instance, this has now changed to be the deserialized message instance.

This may cause tests that rely on messages being the same instance (for example by having mutable messages with attributes
that are asserted in the tests) to not work any more with this setting enabled. For such cases the recommendation is to
either not rely on messages being the same instance or turn the setting off.

### Wire Protocol Compatibility

It is possible to use Akka Remoting between nodes running Akka 2.4.16 and 2.5.x, but some settings have changed so you might need
to adjust some configuration as described in [Rolling Update](#mig25-rolling).

Note however that if using Java serialization it will not be possible to mix nodes using Scala 2.11 and 2.12.

## Cluster

<a id="mig25-rolling"></a>
### Rolling Update

It is possible to do a rolling update from Akka 2.4.16 to 2.5.x, i.e. running a cluster of 2.4.16 nodes and
join nodes running 2.5.x followed by shutting down the old nodes.

You must first update all nodes to 2.4.16. It's not supported to update directly from an older version than
2.4.16 to 2.5.x. For example, if you are running 2.4.11 you must first do a rolling update to 2.4.16, shut down
all 2.4.11 nodes, and then do the rolling update to 2.5.x.

For some configuration settings it's important to use the same values on all nodes in the cluster.
Some settings have changed default value in 2.5.0 and therefore you need to review your configuration
before doing a rolling update to 2.5.x. Such settings are mentioned elsewhere in this migration guide
and here is a summary of things to consider.

 * [akka.actor.additional-serialization-bindings](#mig25-addser)
 * [akka.cluster.allow-weakly-up-members](#mig25-weaklyup)
 * [akka.cluster.sharding.state-store-mode](#mig25-sharding-store)
 * [akka.remote.netty.ssl.require-mutual-authentication](#mig25-mutual)

#### Limit lookup of routees to nodes tagged with multiple roles

Starting with 2.5.4, cluster routing supports delivering messages to routees tagged with all specified roles
using `use-roles` (instead of `use-role` in previous versions). When doing rolling upgrades and using this new feature,
it is important to first upgrade the existing nodes to the latest version of Akka
and then start using multiple roles in a separate rolling upgrade. Otherwise, if a new node sends a message
with the restriction `use-roles = ["a", "b"]`, that will only require the "a" role on old nodes.

### Coordinated Shutdown

There is a new extension named `CoordinatedShutdown` that will stop certain actors and
services in a specific order and perform registered tasks during the shutdown process.

When using Akka Cluster, tasks for graceful leaving of cluster including graceful
shutdown of Cluster Singletons and Cluster Sharding are now performed automatically.

Previously it was documented that things like terminating the `ActorSystem` should be
done when the cluster member was removed, but this was very difficult to get right.
That is now taken care of automatically. This might result in changed behavior, hopefully
to the better. It might also be in conflict with your previous shutdown code so please
read the documentation for the Coordinated Shutdown and revisit your own implementations.
Most likely your implementation will not be needed any more or it can be simplified.

More information can be found in the @ref:[documentation](../actors.md#coordinated-shutdown).

For some tests it might be undesired to terminate the `ActorSystem` via `CoordinatedShutdown`.
You can disable that by adding the following to the configuration of the `ActorSystem` that is
used in the test:

```
# Don't terminate ActorSystem via CoordinatedShutdown in tests
akka.coordinated-shutdown.terminate-actor-system = off
akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
akka.cluster.run-coordinated-shutdown-when-down = off
```

<a id="mig25-weaklyup"></a>
### WeaklyUp

@ref:[WeaklyUp](../cluster-usage.md#weakly-up) is now enabled by default, but it can be disabled with configuration option:

```
akka.cluster.allow-weakly-up-members = off
```

You should not run a cluster with this feature enabled on some nodes and disabled on some. Therefore
you might need to enable/disable it in configuration when performing rolling upgrade from 2.4.x to 2.5.0.

<a id="mig25-sharding-store"></a>
### Cluster Sharding state-store-mode

Distributed Data mode, which was still experimental in 2.4.x, is now the default `state-store-mode` for Cluster Sharding. The persistence mode
is also supported. Read more in the @ref:[documentation](../cluster-sharding.md#cluster-sharding-mode).

It's important to use the same mode on all nodes in the cluster, i.e. if you perform a rolling upgrade
from 2.4.16 you might need to change the `state-store-mode` to be the same (`persistence` is default
in 2.4.x):

```
akka.cluster.sharding.state-store-mode = persistence
```

Note that the stored @ref:[Remembering Entities](../cluster-sharding.md#cluster-sharding-remembering) data with `persistence` mode cannot
be migrated to the `data` mode. Such entities must be started again in some other way when using
`ddata` mode.

Rolling upgrades from clusters that already used the (then-experimental) `ddata`
mode are not supported.

### Cluster Sharding remember entities

To use *remember entities* with cluster sharding there are now an additional requirement added: the
`extractShardId` must be able to extract the shard id from the message `Shard.StartEntity(EntityId)`.
This is implies that it must be possible to calculate a shard id from an entity id when using remember
entities.

This was added to be able to gracefully handle when persisted locations of entities does not match
where the entities should live when a shard region starts up. Such states could be cause by changing
the `extractShardId` logic and restart a system using *remember entities*.


### Cluster Management Command Line Tool

There is a new cluster management tool with HTTP API that has the same functionality as the command line tool.
The HTTP API gives you access to cluster membership information as JSON including full reachability status between the nodes.
It supports the ordinary cluster operations such as join, leave, and down.

See documentation of [Akka Management](http://developer.lightbend.com/docs/akka-management/current/).

The command line script for cluster management has been deprecated and is scheduled for removal
in the next major version. Use the HTTP API with [curl](https://curl.haxx.se/) or similar instead.

## Distributed Data

Distributed Data has been promoted to a stable module. This means that we will keep the API stable from this point. As a result
the module name is changed from *akka-distributed-data-experimental* to *akka-distributed-data* and you need to change that in your
build tool (sbt/mvn/...).

### Map allow generic type for the keys

In 2.4 the key of any Distributed Data map always needed to be of type String. In 2.5 you can use any type for the key. This means that
every map (ORMap, LWWMap, PNCounterMap, ORMultiMap) now takes an extra type parameter to specify the key type. To migrate
existing code from 2.4 to 2.5 you simple add String as key type, for example: *ORMultiMap[Foo]* becomes *ORMultiMap[String, Foo]*.
*PNCounterMap* didn't take a type parameter in version 2.4, so *PNCounterMap* in 2.4 becomes *PNCounterMap[String]* in 2.5.
Java developers should use *<>* instead of *[]*, e.g: *PNCounterMap<String>*.

**NOTE: Even though the interface is not compatible between 2.4 and 2.5, the binary protocol over the wire is (as long
as you use String as key type). This means that 2.4 nodes can synchronize with 2.5 nodes.**

### Subscribers

When an entity is removed subscribers will not receive `Replicator.DataDeleted` any more.
They will receive `Replicator.Deleted` instead.

## Persistence

### Binary incompatibility of PersistentActor and AtLeastOneDelivery

To be able to evolve the Java APIs `AbstractPersistentActor` and `AbstractPersistentActorWithAtLeastOnceDelivery`
to work with Scala 2.12 we could find no other solution but to break the binary compatibility of the Scala versions
(which the Java ones were based on).

This means that the Akka 2.5 artifact cannot be a class path drop in replacement of Akka 2.4 if you use
`PersistentActor` or `AtLeastOnceDelivery`, to do this upgrade you _must_ recompile your project with the new
version of Akka.

### Removal of PersistentView

After being deprecated for a long time, and replaced by
@ref:[Persistence Query](../persistence-query.md) `PersistentView` has been removed now removed.

The corresponding query type is `EventsByPersistenceId`. There are several alternatives for connecting the `Source`
to an actor corresponding to a previous `PersistentView`. There are several alternatives for connecting the `Source`
to an actor corresponding to a previous `PersistentView` actor which are documented in @ref:[Integration](../stream/stream-integrations.md).

The consuming actor may be a plain `Actor` or an `PersistentActor` if it needs to store its own state (e.g. `fromSequenceNr` offset).

Please note that Persistence Query is not experimental/may-change anymore in Akka `2.5.0`, so you can safely upgrade to it.

### Persistence Plugin Proxy

A new @ref:[persistence plugin proxy](../persistence.md#persistence-plugin-proxy) was added, that allows sharing of an otherwise
non-sharable journal or snapshot store. The proxy is available by setting `akka.persistence.journal.plugin` or
`akka.persistence.snapshot-store.plugin` to `akka.persistence.journal.proxy` or `akka.persistence.snapshot-store.proxy`,
respectively. The proxy supplants the @ref:[Shared LevelDB journal](../persistence.md#shared-leveldb-journal).

## Persistence Query

Persistence Query has been promoted to a stable module. As a result the module name is changed from *akka-persistence-query-experimental*
to *akka-persistence-query* and you need to change that in your build tool (sbt/mvn/...).
Only slight API changes were made since the module was introduced:

### Query naming consistency improved

Queries always fall into one of the two categories: infinite or finite ("current").
The naming convention for these categories of queries was solidified and is now as follows:

 * "infinite" - e.g. `eventsByTag`, `persistenceIds` - which will keep emitting events as they are persisted and match the query.
 * "finite", also known as "current" - e.g. `currentEventsByTag`, `currentPersistenceIds` - which will complete the stream once the query completed,
for the journal's definition of "current". For example in an SQL store it would mean it only queries the database once.

Only the `AllPersistenceIdsQuery` class and method name changed due to this.
The class is now called `PersistenceIdsQuery`, and the method which used to be `allPersistenceIds` is now `persistenceIds`.

### Queries now use `Offset` instead of `Long` for offsets

This change was made to better accomodate the various types of Journals and their understanding what an offset is.
For example, in some journals an offset is always a time, while in others it is a numeric offset (like a sequence id).

Instead of the previous `Long` offset you can now use the provided `Offset` factories (and types):

 * `akka.persistence.query.Offset.sequence(value: Long)`,
 * `akka.persistence.query.Offset.timeBasedUUID(value: UUID)`
 * and finally `NoOffset` if not offset should be used.

Journals are also free to provide their own specific `Offset` types. Consult your journal plugin's documentation for details.

## Agents

### Agents are now deprecated

Akka Agents are a very simple way of containing mutable state and allowing to access it safely from
multiple threads. The abstraction is leaky though, as Agents do not work over the network (unlike Akka Actors).

As users were often confused by "when to use an Actor vs. when to use an Agent?" a decision was made to deprecate
the Agents, as they rarely are really enough and do not fit the Akka spirit of thinking about distribution.
We also anticipate to replace the uses of Agents by the upcoming Akka Typed, so in preparation thereof the Agents have been deprecated in 2.5.

If you use Agents and would like to take over the maintenance thereof, please contact the team on Gitter or GitHub.

## Camel

`akka-camel` has been deprecated in favour of [Alpakka](https://github.com/akka/alpakka) ,
the Akka Streams based collection of integrations to various endpoints (including Camel)

We acknowledge that Akka Camel is a very useful and important module. It will not be removed until
Alpakka has reached the needed production quality to be a full replacement. The deprecation of
Akka Camel should be seen as a signal that new development is to be invested in Alpakka instead
of Akka Camel.

## Contrib

`akka-contrib` has been deprecated and is scheduled for removal in the next major version.
The reason is to reduce the amount of things to maintain in the core Akka projects.
Contributions to the core of Akka or its satellite projects are welcome. Contributions
that don't fit into existing modules can be hosted in new Akka GitHub repositories in the
`akka` GitHub organization or outside of it depending on what kind of library it is.
Please ask.

### Aggregator

`Aggregator` has been deprecated. Feel free to copy the source into your project or create a
separate library outside of Akka.

### CircuitBreakerProxy

`CircuitBreakerProxy` has been deprecated in favor of `akka.pattern.CircuitBreaker` with explicit `ask` requests.

### JavaLogger

`akka.contrib.jul.JavaLogger` has been deprecated and included in `akka-actor` instead as
`akka.event.jul.JavaLogger`. See @ref:[documentation](../logging.md#jul).

The `JavaLoggingAdapter` has also been deprecated, but not included in `akka-actor`.
Feel free to copy the source into your project or create a separate library outside of Akka.

### PeekMailbox

`PeekMailbox` has been deprecated. Use an explicit supervisor or proxy actor instead.

<a id="migration-guide-timerbasedthrottler"></a>
### ReceivePipeline

`ReceivePipeline` has been deprecated. Feel free to copy the source into your project or create
a separate library outside of Akka.

### ReliableProxy

`ReliableProxy` has been deprecated. Use @ref:[At-Least-Once Delivery](../persistence.md#at-least-once-delivery) instead. `ReliableProxy`
was only intended as an example and doesn't have full production quality. If there is demand
for a lightweight (non-durable) at-least once delivery mechanism we are open for a design discussion.

### TimerBasedThrottler

`TimerBasedThrottler` has been deprecated. Use the `throttle` stage in Akka Streams instead.

Example in Scala:

```
import scala.concurrent.duration._
import akka.NotUsed
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

val system: ActorSystem = ??? // TODO real ActorSystem here
val target: ActorRef = ??? // TODO real target ActorRef here
implicit val materializer = ActorMaterializer.create(system)

val throttler: ActorRef =
  Source.actorRef(bufferSize = 1000, OverflowStrategy.dropNew)
    .throttle(100, 1.second)
    .to(Sink.actorRef(target, NotUsed))
    .run()
```

Example in Java:

```
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.ThrottleMode;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

final ActorSystem system = null; // TODO real ActorSystem here
final ActorRef target = null; // TODO real target ActorRef here
final Materializer materializer = ActorMaterializer.create(system);

final ActorRef throttler =
  Source.actorRef(1000, OverflowStrategy.dropNew())
    .throttle(100,  Duration.ofSeconds(1))
    .to(Sink.actorRef(target, NotUsed.getInstance()))
    .run(materializer);
```

Note, that when using `Sink.actorRef` the sender of the original message sent to the `thottler` ActorRef will be lost and messages will arrive at the `target` with the sender set to `ActorRef.noSender`. Using this construct it is currently impossible to keep the original sender. Alternatively, you can manually specify a static sender by replacing `Sink.actorRef` with @java[`Sink.foreach(msg -> target.tell(msg, whateverSender))`]@scala[`Sink.foreach(msg => target.tell(msg, whateverSender))`]. You could also calculate a sender by inspecting the `msg`. Be cautious not to use `target.tell(msg, sender())` inside of `Sink.foreach` because the result of `sender()` is undefined or will fail when executed from within a stream.

## Akka Typed

With the new term @ref:[may change](../common/may-change.md) we will no longer have a different artifact for modules that are not
stable, and `akka-typed-experimental` has therefore been renamed to `akka-typed`. Note that it is still not
promoted to a stable module.

## Experimental modules

We have previously marked modules that we did not want to freeze the APIs of a **experimental**, such modules will
instead be marked as @ref:[may change](../common/may-change.md) from now on.

## Testkit

`JavaTestKit` has been deprecated since it does all kinds of tricks to achieve what Scala testkit does.
Use `akka.testkit.javadsl.TestKit` instead which introduces nicer APIs.

Old:

```
new JavaTestKit(system) {{
  final JavaTestKit probe = new JavaTestKit(system);
  new Within(duration("1 second")) {
    public void run() {
      probe.expectMsgEquals("hello");
    }
  };
}};
```

New:

```
new TestKit(system) {{
  final TestKit probe = new TestKit(system);
  within(duration("1 second"), () -> probe.expectMsgEquals("hello"));
}};
```
