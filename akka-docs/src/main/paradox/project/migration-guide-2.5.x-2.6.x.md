# Migration Guide 2.5.x to 2.6.x

## Default remoting is now Artery TCP

@ref[Artery TCP](../remoting-artery.md) is now the default remoting implementation. 
Classic remoting has been deprecated and will be removed in `2.7.x`.
To migrate to Artery a full cluster restart is required. If you've already moved to Artery in 2.5.x
then no changes are required and a normal rolling restart is possible.

Configuration for artery is under `akka.remote.artery` configuration for classic remoting in
`2.6` has moved from `akka.remote` to `akka.remote.classic`. Configuration that is used for both
remains under `akka.remote`. 

### Switching to Artery

To switch to Artery any overrides for classic remoting need to be ported to Artery configuration.
The most likely are:

Hostname and port for binding:

* `akka.remote.netty.tcp.hostname` => `akka.remote.artery.canonical.hostname`
* `akka.remote.netty.tcp.port`=> `akka.remote.artery.canonical.port`

If using SSL then `tcp-tls` needs to be enabled and setup. See @ref[Artery docs for SSL](../remoting-artery.md#configuring-ssl-tls-for-akka-remoting)
for how to do this.


### Remaining with Classic remoting (not recommended)

Classic remoting is deprecated but can be used in `2.6.` Any configuration under `akka.remote` that is 
specific to classic remoting needs to be moved to `akka.remote.classic`. To see which configuration options
are specific to classic search for them in: [`akka-remote/reference.conf`](/akka-remote/src/main/resources/reference.conf)

## Netty UDP has been removed

Classic remoting over UDP has been deprecated since `2.5.0` and now has been removed. 
To continue to use UDP configure @ref[Artery UDP](../remoting-artery.md#configuring-ssl-tls-for-akka-remoting) or migrate to Artery TCP.
A full cluster restart is required to change to Artery.

## Scala 2.11 no longer supported

If you are still using Scala 2.11 then you must upgrade to 2.12 or 2.13
