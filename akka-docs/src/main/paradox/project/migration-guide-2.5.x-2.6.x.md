# Migration Guide 2.5.x to 2.6.x

## akka-camel removed

After being deprecated in 2.5.0, the akka-camel module has been removed in 2.6.
As an alternative we recommend [Alpakka](https://doc.akka.io/docs/alpakka/current/).

This is of course not a drop-in replacement. If there is community interest we
are open to setting up akka-camel as a separate community-maintained
repository.

## akka-agent removed

After being deprecated in 2.5.0, the akka-agent module has been removed in 2.6.
If there is interest it may be moved to a separate, community-maintained
repository.

## Scala 2.11 no longer supported

If you are still using Scala 2.11 then you must upgrade to 2.12 or 2.13

### Actor DSL removal

Actor DSL is a rarely used feature and has been deprecated since `2.5.0`.
Use plain `system.actorOf` instead of the DSL to create Actors if you have been using it.

## Default remoting is now Artery TCP

@ref[Artery TCP](../remoting-artery.md) is now the default remoting implementation. 
Classic remoting has been deprecated and will be removed in `2.7.0`.

<a id="classic-to-artery"></a>
### Migrating from classic remoting to Artery

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


### Migration from 2.5.x Artery to 2.6.x Artery

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


### Remaining with Classic remoting (not recommended)

Classic remoting is deprecated but can be used in `2.6.` Any configuration under `akka.remote` that is 
specific to classic remoting needs to be moved to `akka.remote.classic`. To see which configuration options
are specific to classic search for them in: [`akka-remote/reference.conf`](/akka-remote/src/main/resources/reference.conf)

## Netty UDP has been removed

Classic remoting over UDP has been deprecated since `2.5.0` and now has been removed. 
To continue to use UDP configure @ref[Artery UDP](../remoting-artery.md#configuring-ssl-tls-for-akka-remoting) or migrate to Artery TCP.
A full cluster restart is required to change to Artery.

## Cluster Sharding

### Passivate idle entity
The configuration `akka.cluster.sharding.passivate-idle-entity-after` is now enabled by default.
Sharding will passivate entities when they have not received any messages after this duration.
Set