# Remoting

## Dependency

To use Akka Remoting, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-remote_$scala.binary_version$
  version=$akka.version$
}

## Configuration

To enable remote capabilities in your Akka project you should, at a minimum, add the following changes
to your `application.conf` file:

```
akka {
  actor {
    provider = remote
  }
  remote {
    enabled-transports = ["akka.remote.netty.tcp"]
    netty.tcp {
      hostname = "127.0.0.1"
      port = 2552
    }
 }
}
```

As you can see in the example above there are four things you need to add to get started:

 * Change provider from `local` to `remote`
 * Add host name - the machine you want to run the actor system on; this host
name is exactly what is passed to remote systems in order to identify this
system and consequently used for connecting back to this system if need be,
hence set it to a reachable IP address or resolvable name in case you want to
communicate across the network.
 * Add port number - the port the actor system should listen on, set to 0 to have it chosen automatically

@@@ note

The port number needs to be unique for each actor system on the same machine even if the actor
systems have different names. This is because each actor system has its own networking subsystem
listening for connections and handling messages as not to interfere with other actor systems.

@@@

The example above only illustrates the bare minimum of properties you have to add to enable remoting.
All settings are described in [Remote Configuration](#remote-configuration).

## Introduction

We recommend @ref:[Akka Cluster](cluster-usage.md) over using remoting directly. As remoting is the
underlying module that allows for Cluster, it is still useful to understand details about it though.

For an introduction of remoting capabilities of Akka please see @ref:[Location Transparency](general/remoting.md).

@@@ note

As explained in that chapter Akka remoting is designed for communication in a
peer-to-peer fashion and it is not a good fit for client-server setups. In
particular Akka Remoting does not work transparently with Network Address Translation,
Load Balancers, or in Docker containers. For symmetric communication in these situations
network and/or Akka configuration will have to be changed as described in
[Akka behind NAT or in a Docker container](#remote-configuration-nat).

@@@

## Types of Remote Interaction

Akka has two ways of using remoting:

 * Lookup    : used to look up an actor on a remote node with `actorSelection(path)`
 * Creation  : used to create an actor on a remote node with `actorOf(Props(...), actorName)`

In the next sections the two alternatives are described in detail.

## Looking up Remote Actors

`actorSelection(path)` will obtain an `ActorSelection` to an Actor on a remote node, e.g.:

Scala
:   ```
val selection =
  context.actorSelection("akka.tcp://actorSystemName@10.0.0.1:2552/user/actorName")
```

Java
:   ```
ActorSelection selection =
  context.actorSelection("akka.tcp://app@10.0.0.1:2552/user/serviceA/worker");
```

As you can see from the example above the following pattern is used to find an actor on a remote node:

```
akka.<protocol>://<actor system name>@<hostname>:<port>/<actor path>
```

Once you obtained a selection to the actor you can interact with it in the same way you would with a local actor, e.g.:

Scala
:   ```
selection ! "Pretty awesome feature"
```

Java
:   ```
selection.tell("Pretty awesome feature", getSelf());
```

To acquire an `ActorRef` for an `ActorSelection` you need to
send a message to the selection and use the `sender` reference of the reply from
the actor. There is a built-in `Identify` message that all Actors will understand
and automatically reply to with a `ActorIdentity` message containing the
`ActorRef`. This can also be done with the `resolveOne` method of
the `ActorSelection`, which returns a @scala[`Future`]@java[`CompletionStage`] of the matching
`ActorRef`.

@@@ note

For more details on how actor addresses and paths are formed and used, please refer to @ref:[Actor References, Paths and Addresses](general/addressing.md).

@@@

@@@ note

Message sends to actors that are actually in the sending actor system do not
get delivered via the remote actor ref provider. They're delivered directly,
by the local actor ref provider.

Aside from providing better performance, this also means that if the hostname
you configure remoting to listen as cannot actually be resolved from within
the very same actor system, such messages will (perhaps counterintuitively)
be delivered just fine.

@@@

## Creating Actors Remotely

If you want to use the creation functionality in Akka remoting you have to further amend the
`application.conf` file in the following way (only showing deployment section):

```
akka {
  actor {
    deployment {
      /sampleActor {
        remote = "akka.tcp://sampleActorSystem@127.0.0.1:2553"
      }
    }
  }
}
```

The configuration above instructs Akka to react when an actor with path `/sampleActor` is created, i.e.
using @scala[`system.actorOf(Props(...), "sampleActor")`]@java[`system.actorOf(new Props(...), "sampleActor")`]. This specific actor will not be directly instantiated,
but instead the remote daemon of the remote system will be asked to create the actor,
which in this sample corresponds to `sampleActorSystem@127.0.0.1:2553`.

Once you have configured the properties above you would do the following in code:

Scala
:   @@snip [RemoteDeploymentDocSpec.scala](/akka-docs/src/test/scala/docs/remoting/RemoteDeploymentDocSpec.scala) { #sample-actor }

Java
:   @@snip [RemoteDeploymentDocTest.java](/akka-docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #sample-actor }

The actor class `SampleActor` has to be available to the runtimes using it, i.e. the classloader of the
actor systems has to have a JAR containing the class.

@@@ note

In order to ensure serializability of `Props` when passing constructor
arguments to the actor being created, do not make the factory @scala[an]@java[a non-static] inner class:
this will inherently capture a reference to its enclosing object, which in
most cases is not serializable. It is best to @scala[create a factory method in the
companion object of the actor’s class]@java[make a static
inner class which implements `Creator<T extends Actor>`].

Serializability of all Props can be tested by setting the configuration item
`akka.actor.serialize-creators=on`. Only Props whose `deploy` has
`LocalScope` are exempt from this check.

@@@

@@@ note

You can use asterisks as wildcard matches for the actor path sections, so you could specify:
`/*/sampleActor` and that would match all `sampleActor` on that level in the hierarchy.
You can also use wildcard in the last position to match all actors at a certain level:
`/someParent/*`. Non-wildcard matches always have higher priority to match than wildcards, so:
`/foo/bar` is considered **more specific** than `/foo/*` and only the highest priority match is used.
Please note that it **cannot** be used to partially match section, like this: `/foo*/bar`, `/f*o/bar` etc.

@@@

### Programmatic Remote Deployment

To allow dynamically deployed systems, it is also possible to include
deployment configuration in the `Props` which are used to create an
actor: this information is the equivalent of a deployment section from the
configuration file, and if both are given, the external configuration takes
precedence.

With these imports:

Scala
:   @@snip [RemoteDeploymentDocSpec.scala](/akka-docs/src/test/scala/docs/remoting/RemoteDeploymentDocSpec.scala) { #import }

Java
:   @@snip [RemoteDeploymentDocTest.java](/akka-docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #import }

and a remote address like this:

Scala
:   @@snip [RemoteDeploymentDocSpec.scala](/akka-docs/src/test/scala/docs/remoting/RemoteDeploymentDocSpec.scala) { #make-address }

Java
:   @@snip [RemoteDeploymentDocTest.java](/akka-docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #make-address }

you can advise the system to create a child on that remote node like so:

Scala
:   @@snip [RemoteDeploymentDocSpec.scala](/akka-docs/src/test/scala/docs/remoting/RemoteDeploymentDocSpec.scala) { #deploy }

Java
:   @@snip [RemoteDeploymentDocTest.java](/akka-docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #deploy }

<a id="remote-deployment-whitelist"></a>
### Remote deployment whitelist

As remote deployment can potentially be abused by both users and even attackers a whitelist feature
is available to guard the ActorSystem from deploying unexpected actors. Please note that remote deployment
is *not* remote code loading, the Actors class to be deployed onto a remote system needs to be present on that
remote system. This still however may pose a security risk, and one may want to restrict remote deployment to
only a specific set of known actors by enabling the whitelist feature.

To enable remote deployment whitelisting set the `akka.remote.deployment.enable-whitelist` value to `on`.
The list of allowed classes has to be configured on the "remote" system, in other words on the system onto which
others will be attempting to remote deploy Actors. That system, locally, knows best which Actors it should or
should not allow others to remote deploy onto it. The full settings section may for example look like this:

@@snip [RemoteDeploymentWhitelistSpec.scala](/akka-remote/src/test/scala/akka/remote/RemoteDeploymentWhitelistSpec.scala) { #whitelist-config }

Actor classes not included in the whitelist will not be allowed to be remote deployed onto this system.

## Lifecycle and Failure Recovery Model

![association_lifecycle.png](./images/association_lifecycle.png)

Each link with a remote system can be in one of the four states as illustrated above. Before any communication
happens with a remote system at a given `Address` the state of the association is `Idle`. The first time a message
is attempted to be sent to the remote system or an inbound connection is accepted the state of the link transitions to
`Active` denoting that the two systems has messages to send or receive and no failures were encountered so far.
When a communication failure happens and the connection is lost between the two systems the link becomes `Gated`.

In this state the system will not attempt to connect to the remote host and all outbound messages will be dropped. The time
while the link is in the `Gated` state is controlled by the setting `akka.remote.retry-gate-closed-for`:
after this time elapses the link state transitions to `Idle` again. `Gate` is one-sided in the
sense that whenever a successful *inbound* connection is accepted from a remote system during `Gate` it automatically
transitions to `Active` and communication resumes immediately.

In the face of communication failures that are unrecoverable because the state of the participating systems are inconsistent,
the remote system becomes `Quarantined`. Unlike `Gate`, quarantining is permanent and lasts until one of the systems
is restarted. After a restart communication can be resumed again and the link can become `Active` again.

## Watching Remote Actors

Watching a remote actor is not different than watching a local actor, as described in
@ref:[Lifecycle Monitoring aka DeathWatch](actors.md#deathwatch).

### Failure Detector

Under the hood remote death watch uses heartbeat messages and a failure detector to generate `Terminated`
message from network failures and JVM crashes, in addition to graceful termination of watched
actor.

The heartbeat arrival times is interpreted by an implementation of
[The Phi Accrual Failure Detector](http://www.jaist.ac.jp/~defago/files/pdf/IS_RR_2004_010.pdf).

The suspicion level of failure is given by a value called *phi*.
The basic idea of the phi failure detector is to express the value of *phi* on a scale that
is dynamically adjusted to reflect current network conditions.

The value of *phi* is calculated as:

```
phi = -log10(1 - F(timeSinceLastHeartbeat))
```

where F is the cumulative distribution function of a normal distribution with mean
and standard deviation estimated from historical heartbeat inter-arrival times.

In the [Remote Configuration](#remote-configuration) you can adjust the `akka.remote.watch-failure-detector.threshold`
to define when a *phi* value is considered to be a failure.

A low `threshold` is prone to generate many false positives but ensures
a quick detection in the event of a real crash. Conversely, a high `threshold`
generates fewer mistakes but needs more time to detect actual crashes. The
default `threshold` is 10 and is appropriate for most situations. However in
cloud environments, such as Amazon EC2, the value could be increased to 12 in
order to account for network issues that sometimes occur on such platforms.

The following chart illustrates how *phi* increase with increasing time since the
previous heartbeat.

![phi1.png](./images/phi1.png)

Phi is calculated from the mean and standard deviation of historical
inter arrival times. The previous chart is an example for standard deviation
of 200 ms. If the heartbeats arrive with less deviation the curve becomes steeper,
i.e. it is possible to determine failure more quickly. The curve looks like this for
a standard deviation of 100 ms.

![phi2.png](./images/phi2.png)

To be able to survive sudden abnormalities, such as garbage collection pauses and
transient network failures the failure detector is configured with a margin,
`akka.remote.watch-failure-detector.acceptable-heartbeat-pause`. You may want to
adjust the [Remote Configuration](#remote-configuration) of this depending on you environment.
This is how the curve looks like for `acceptable-heartbeat-pause` configured to
3 seconds.

![phi3.png](./images/phi3.png)

## Serialization

When using remoting for actors you must ensure that the `props` and `messages` used for
those actors are serializable. Failing to do so will cause the system to behave in an unintended way.

For more information please see @ref:[Serialization](serialization.md).

<a id="disable-java-serializer"></a>
### Disabling the Java Serializer

It is highly recommended that you @ref[disable Java serialization](serialization.md#disable-java-serializer).

## Routers with Remote Destinations

It is absolutely feasible to combine remoting with @ref:[Routing](routing.md).

A pool of remote deployed routees can be configured as:

@@snip [RouterDocSpec.scala](/akka-docs/src/test/scala/docs/routing/RouterDocSpec.scala) { #config-remote-round-robin-pool }

This configuration setting will clone the actor defined in the `Props` of the `remotePool` 10
times and deploy it evenly distributed across the two given target nodes.

A group of remote actors can be configured as:

@@snip [RouterDocSpec.scala](/akka-docs/src/test/scala/docs/routing/RouterDocSpec.scala) { #config-remote-round-robin-group }

This configuration setting will send messages to the defined remote actor paths.
It requires that you create the destination actors on the remote nodes with matching paths.
That is not done by the router.

### Remote Events

It is possible to listen to events that occur in Akka Remote, and to subscribe/unsubscribe to these events
you register as listener to the below described types in on the `ActorSystem.eventStream`.

@@@ note

To subscribe to any remote event, subscribe to
`RemotingLifecycleEvent`.  To subscribe to events related only to
the lifecycle of associations, subscribe to
`akka.remote.AssociationEvent`.

@@@

@@@ note

The use of term "Association" instead of "Connection" reflects that the
remoting subsystem may use connectionless transports, but an association
similar to transport layer connections is maintained between endpoints by
the Akka protocol.

@@@

By default an event listener is registered which logs all of the events
described below. This default was chosen to help setting up a system, but it is
quite common to switch this logging off once that phase of the project is
finished.

@@@ note

In order to switch off the logging, set
`akka.remote.log-remote-lifecycle-events = off` in your
`application.conf`.

@@@

To be notified when an association is over ("disconnected") listen to `DisassociatedEvent` which
holds the direction of the association (inbound or outbound) and the addresses of the involved parties.

To be notified  when an association is successfully established ("connected") listen to `AssociatedEvent` which
holds the direction of the association (inbound or outbound) and the addresses of the involved parties.

To intercept errors directly related to associations, listen to `AssociationErrorEvent` which
holds the direction of the association (inbound or outbound), the addresses of the involved parties and the
`Throwable` cause.

To be notified  when the remoting subsystem is ready to accept associations, listen to `RemotingListenEvent` which
contains the addresses the remoting listens on.

To be notified when the current system is quarantined by the remote system, listen to `ThisActorSystemQuarantinedEvent`,
which includes the addresses of local and remote ActorSystems.

To be notified  when the remoting subsystem has been shut down, listen to `RemotingShutdownEvent`.

To intercept generic remoting related errors, listen to `RemotingErrorEvent` which holds the `Throwable` cause.

<a id="remote-security"></a>
## Remote Security

An `ActorSystem` should not be exposed via Akka Remote over plain TCP to an untrusted network (e.g. Internet).
It should be protected by network security, such as a firewall. If that is not considered as enough protection
[TLS with mutual authentication](#remote-tls)  should be enabled.

Best practice is that Akka remoting nodes should only be accessible from the adjacent network. Note that if TLS is
enabled with mutual authentication there is still a risk that an attacker can gain access to a valid certificate by
compromising any node with certificates issued by the same internal PKI tree.

It is also security best-practice to [disable the Java serializer](#disable-java-serializer) because of
its multiple [known attack surfaces](https://community.hpe.com/t5/Security-Research/The-perils-of-Java-deserialization/ba-p/6838995).

<a id="remote-tls"></a>
### Configuring SSL/TLS for Akka Remoting

SSL can be used as the remote transport by adding `akka.remote.netty.ssl` to the `enabled-transport` configuration section.
An example of setting up the default Netty based SSL driver as default:

```
akka {
  remote {
    enabled-transports = [akka.remote.netty.ssl]
  }
}
```

Next the actual SSL/TLS parameters have to be configured:

```
akka {
  remote {
    netty.ssl {
      hostname = "127.0.0.1"
      port = "3553"

      security {
        key-store = "/example/path/to/mykeystore.jks"
        trust-store = "/example/path/to/mytruststore.jks"

        key-store-password = ${SSL_KEY_STORE_PASSWORD}
        key-password = ${SSL_KEY_PASSWORD}
        trust-store-password = ${SSL_TRUST_STORE_PASSWORD}

        protocol = "TLSv1.2"

        enabled-algorithms = [TLS_DHE_RSA_WITH_AES_128_GCM_SHA256]
      }
    }
  }
}
```

Always use [substitution from environment variables](https://github.com/lightbend/config#optional-system-or-env-variable-overrides)
for passwords. Don't define real passwords in config files.

According to [RFC 7525](https://tools.ietf.org/html/rfc7525) the recommended algorithms to use with TLS 1.2 (as of writing this document) are:

 * TLS_DHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_DHE_RSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384

You should always check the latest information about security and algorithm recommendations though before you configure your system.

Creating and working with keystores and certificates is well documented in the
[Generating X.509 Certificates](http://lightbend.github.io/ssl-config/CertificateGeneration.html#using-keytool)
section of Lightbend's SSL-Config library.

Since an Akka remoting is inherently @ref:[peer-to-peer](general/remoting.md#symmetric-communication) both the key-store as well as trust-store
need to be configured on each remoting node participating in the cluster.

The official [Java Secure Socket Extension documentation](http://docs.oracle.com/javase/7/docs/technotes/guides/security/jsse/JSSERefGuide.html)
as well as the [Oracle documentation on creating KeyStore and TrustStores](https://docs.oracle.com/cd/E19509-01/820-3503/6nf1il6er/index.html)
are both great resources to research when setting up security on the JVM. Please consult those resources when troubleshooting
and configuring SSL.

Since Akka 2.5.0 mutual authentication between TLS peers is enabled by default.

Mutual authentication means that the the passive side (the TLS server side) of a connection will also request and verify
a certificate from the connecting peer. Without this mode only the client side is requesting and verifying certificates.
While Akka is a peer-to-peer technology, each connection between nodes starts out from one side (the "client") towards
the other (the "server").

Note that if TLS is enabled with mutual authentication there is still a risk that an attacker can gain access to a valid certificate
by compromising any node with certificates issued by the same internal PKI tree.

See also a description of the settings in the @ref:[Remote Configuration](remoting.md#remote-configuration) section.

@@@ note

When using SHA1PRNG on Linux it's recommended specify `-Djava.security.egd=file:/dev/urandom` as argument
to the JVM to prevent blocking. It is NOT as secure because it reuses the seed.

@@@

### Untrusted Mode

As soon as an actor system can connect to another remotely, it may in principle
send any possible message to any actor contained within that remote system. One
example may be sending a `PoisonPill` to the system guardian, shutting
that system down. This is not always desired, and it can be disabled with the
following setting:

```
akka.remote.untrusted-mode = on
```

This disallows sending of system messages (actor life-cycle commands,
DeathWatch, etc.) and any message extending `PossiblyHarmful` to the
system on which this flag is set. Should a client send them nonetheless they
are dropped and logged (at DEBUG level in order to reduce the possibilities for
a denial of service attack). `PossiblyHarmful` covers the predefined
messages like `PoisonPill` and `Kill`, but it can also be added
as a marker trait to user-defined messages.

@@@ warning

Untrusted mode does not give full protection against attacks by itself.
It makes it slightly harder to perform malicious or unintended actions but
it should be complemented with [disabled Java serializer](#disable-java-serializer).
Additional protection can be achieved when running in an untrusted network by
network security (e.g. firewalls) and/or enabling [TLS with mutual authentication](#remote-tls).

@@@

Messages sent with actor selection are by default discarded in untrusted mode, but
permission to receive actor selection messages can be granted to specific actors
defined in configuration:

```
akka.remote.trusted-selection-paths = ["/user/receptionist", "/user/namingService"]
```

The actual message must still not be of type `PossiblyHarmful`.

In summary, the following operations are ignored by a system configured in
untrusted mode when incoming via the remoting layer:

 * remote deployment (which also means no remote supervision)
 * remote DeathWatch
 * `system.stop()`, `PoisonPill`, `Kill`
 * sending any message which extends from the `PossiblyHarmful` marker
interface, which includes `Terminated`
 * messages sent with actor selection, unless destination defined in `trusted-selection-paths`.

@@@ note

Enabling the untrusted mode does not remove the capability of the client to
freely choose the target of its message sends, which means that messages not
prohibited by the above rules can be sent to any actor in the remote system.
It is good practice for a client-facing system to only contain a well-defined
set of entry point actors, which then forward requests (possibly after
performing validation) to another actor system containing the actual worker
actors. If messaging between these two server-side systems is done using
local `ActorRef` (they can be exchanged safely between actor systems
within the same JVM), you can restrict the messages on this interface by
marking them `PossiblyHarmful` so that a client cannot forge them.

@@@

<a id="remote-configuration"></a>
## Remote Configuration

There are lots of configuration properties that are related to remoting in Akka. We refer to the
@ref:[reference configuration](general/configuration.md#config-akka-remote) for more information.

@@@ note

Setting properties like the listening IP and port number programmatically is
best done by using something like the following:

@@snip [RemoteDeploymentDocTest.java](/akka-docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #programmatic }

@@@

<a id="remote-configuration-nat"></a>
### Akka behind NAT or in a Docker container

In setups involving Network Address Translation (NAT), Load Balancers or Docker
containers the hostname and port pair that Akka binds to will be different than the "logical"
host name and port pair that is used to connect to the system from the outside. This requires
special configuration that sets both the logical and the bind pairs for remoting.

```ruby
akka {
  remote {
    netty.tcp {
      hostname = my.domain.com      # external (logical) hostname
      port = 8000                   # external (logical) port

      bind-hostname = local.address # internal (bind) hostname
      bind-port = 2552              # internal (bind) port
    }
 }
}
```

Keep in mind that local.address will most likely be in one of private network ranges:

 * *10.0.0.0 - 10.255.255.255* (network class A)
 * *172.16.0.0 - 172.31.255.255* (network class B)
 * *192.168.0.0 - 192.168.255.255* (network class C)

For further details see [RFC 1597](https://tools.ietf.org/html/rfc1597) and [RFC 1918](https://tools.ietf.org/html/rfc1918).

You can look at the
@java[@extref[Cluster with docker-compse example project](samples:akka-sample-cluster-docker-compose-java)]
@scala[@extref[Cluster with docker-compose example project](samples:akka-sample-cluster-docker-compose-scala)]
to see what this looks like in practice.
