# Migration Guide 2.5.x to 2.6.x

## akka-camel removed

After being deprecated in 2.5.0, the akka-camel module has been removed in 2.6.
As an alternative we recommend [Alpakka](https://doc.akka.io/docs/alpakka/current/).

This is of course not a drop-in replacement. If there is community interest we
are open to setting up akka-camel as a separate community-maintained
repository.

## Scala 2.11 no longer supported

If you are still using Scala 2.11 then you must upgrade to 2.12 or 2.13

### Actor DSL removal

Actor DSL is a rarely used feature and has been deprecated since `2.5.0`.
Use plain `system.actorOf` instead of the DSL to create Actors if you have been using it.

## Default remoting is now Artery TCP

@ref[Artery TCP](../remoting-artery.md) is now the default remoting implementation. 
Classic remoting has been deprecated and will be removed in `2.7.0`.
To migrate to Artery a full cluster restart is required. If you've already moved to Artery in 2.5.x
then a normal rolling restart is supported but some configuration properties have moved (see below).

Configuration for artery is under `akka.remote.artery` configuration for classic remoting in
`2.6` has moved from `akka.remote` to `akka.remote.classic`. Configuration that is used for both
remains under `akka.remote`. 

### Switching to Artery

To switch to Artery a full cluster restart is required and any overrides for classic remoting need to be ported to Artery configuration.
The most likely are. See [migrating from classic remoting to Artery](../remoting-artery.md#migrating-from-classic-remoting)


### Migration from 2.5.x Artery to 2.6.x Artery

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
