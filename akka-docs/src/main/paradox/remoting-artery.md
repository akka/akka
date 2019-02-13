# Remoting (codename Artery)

## Dependency

To use Remoting (codename Artery), you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-remote_$scala.binary_version$
  version=$akka.version$
}

If migrating from classic remoting see @ref:[what's new in Artery](#what-is-new-in-artery)

## Configuration

To enable remote capabilities in your Akka project you should, at a minimum, add the following changes
to your `application.conf` file:

```
akka {
  actor {
    provider = cluster 
  }
  remote {
    artery {
      enabled = on
      transport = aeron-udp # See Selecting a transport below
      canonical.hostname = "127.0.0.1"
      canonical.port = 25520
    }
  }
}
```

As you can see in the example above there are four things you need to add to get started:

 * Change provider from `local` to `cluster`
 * Enable Artery to use it as the remoting implementation
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
All settings are described in @ref:[Remote Configuration](#remote-configuration-artery).

## Introduction

We recommend @ref:[Akka Cluster](cluster-usage.md) over using remoting directly. As remoting is the
underlying module that allows for Cluster, it is still useful to understand details about it though.

@@@ note

This page describes the remoting subsystem, codenamed *Artery* that will eventually replace the
@ref:[old remoting implementation](remoting.md). Artery with the Aeron transport is ready
to use in production. The TCP based transport is not ready for use in production yet. The module is
marked @ref:[may change](common/may-change.md) because some configuration will be changed when the API
becomes stable.

@@@

Remoting enables Actor systems on different hosts or JVMs to communicate with each other. By enabling remoting
the system will start listening on a provided network address and also gains the ability to connect to other
systems through the network. From the application's perspective there is no API difference between local or remote
systems, `ActorRef` instances that point to remote systems look exactly the same as local ones: they can be
sent messages to, watched, etc.
Every `ActorRef` contains hostname and port information and can be passed around even on the network. This means
that on a network every `ActorRef` is a unique identifier of an actor on that network.

Remoting is not a server-client technology. All systems using remoting can contact any other system on the network
if they possess an `ActorRef` pointing to those system. This means that every system that is remoting enabled
acts as a "server" to which arbitrary systems on the same network can connect to.

## Selecting a transport

There are three alternatives of which underlying transport to use. It is configured by property
`akka.remote.artery.transport` with the possible values:

* `aeron-udp` - Based on [Aeron (UDP)](https://github.com/real-logic/aeron)
* `tcp` - Based on @ref:[Akka Streams TCP](stream/stream-io.md#streaming-tcp)
* `tls-tcp` - Same as `tcp` with encryption using @ref:[Akka Streams TLS](stream/stream-io.md#tls)

The Aeron (UDP) transport is a high performance transport and should be used for systems
that require high throughput and low latency. It uses more CPU than TCP when the system
is idle or at low message rates. There is no encryption for Aeron.

The TCP and TLS transport is implemented using Akka Streams TCP/TLS. This is the choice
when encryption is needed, but it can also be used with plain TCP without TLS. It's also
the obvious choice when UDP can't be used.
It has very good performance (high throughput and low latency) but latency at high throughput
might not be as good as the Aeron transport.

@@@ note

Aeron requires 64bit JVM to work reliably and is only officially supported on Linux, Mac and Windows.
It may work on other Unixes e.g. Solaris but insufficient testing has taken place for it to be
officially supported. If you're on a Big Endian processor, such as Sparc, it is recommended to use
 TCP.

@@@



### Canonical address

In order to remoting to work properly, where each system can send messages to any other system on the same network
(for example a system forwards a message to a third system, and the third replies directly to the sender system)
it is essential for every system to have a *unique, globally reachable* address and port. This address is part of the
unique name of the system and will be used by other systems to open a connection to it and send messages. This means
that if a host has multiple names (different DNS records pointing to the same IP address) then only one of these
can be *canonical*. If a message arrives to a system but it contains a different hostname than the expected canonical
name then the message will be dropped. If multiple names for a system would be allowed, then equality checks among
`ActorRef` instances would no longer to be trusted and this would violate the fundamental assumption that
an actor has a globally unique reference on a given network. As a consequence, this also means that localhost addresses
(e.g. *127.0.0.1*) cannot be used in general (apart from local development) since they are not unique addresses in a
real network.

In cases, where Network Address Translation (NAT) is used or other network bridging is involved, it is important
to configure the system so that it understands that there is a difference between his externally visible, canonical
address and between the host-port pair that is used to listen for connections. See [Akka behind NAT or in a Docker container](#remote-configuration-nat-artery)
for details.

## Acquiring references to remote actors

In order to communicate with an actor, it is necessary to have its `ActorRef`. In the local case it is usually
the creator of the actor (the caller of `actorOf()`) is who gets the `ActorRef` for an actor that it can
then send to other actors. In other words:

 * An Actor can get a remote Actor's reference by receiving a message from it (as it's available as @scala[`sender()`]@java[`getSender()`] then),
or inside of a remote message (e.g. *PleaseReply(message: String, remoteActorRef: ActorRef)*)

Alternatively, an actor can look up another located at a known path using
`ActorSelection`. These methods are available even in remoting enabled systems:

 * Remote Lookup    : used to look up an actor on a remote node with `actorSelection(path)`
 * Remote Creation  : used to create an actor on a remote node with `actorOf(Props(...), actorName)`

In the next sections the two alternatives are described in detail.

### Looking up Remote Actors

`actorSelection(path)` will obtain an `ActorSelection` to an Actor on a remote node, e.g.:

Scala
:   ```
    val selection =
      context.actorSelection("akka://actorSystemName@10.0.0.1:25520/user/actorName")
    ```
    
Java
:   ```
    ActorSelection selection =
      context.actorSelection("akka://actorSystemName@10.0.0.1:25520/user/actorName");
    ```
    

As you can see from the example above the following pattern is used to find an actor on a remote node:

```
akka://<actor system>@<hostname>:<port>/<actor path>
```

@@@ note

Unlike with earlier remoting, the protocol field is always *akka* as pluggable transports are no longer supported.

@@@

Once you obtained a selection to the actor you can interact with it in the same way you would with a local actor, e.g.:

Scala
:   @@@vars
    ```
    selection ! "Pretty awesome feature"
    ```
    @@@

Java
:   @@@vars
    ```
    selection.tell("Pretty awesome feature", getSelf());
    ```
    @@@


To acquire an `ActorRef` for an `ActorSelection` you need to
send a message to the selection and use the `sender` reference of the reply from
the actor. There is a built-in `Identify` message that all Actors will understand
and automatically reply to with a `ActorIdentity` message containing the
`ActorRef`. This can also be done with the `resolveOne` method of
the `ActorSelection`, which returns a `Future` of the matching
`ActorRef`.

For more details on how actor addresses and paths are formed and used, please refer to @ref:[Actor References, Paths and Addresses](general/addressing.md).

@@@ note

Message sends to actors that are actually in the sending actor system do not
get delivered via the remote actor ref provider. They're delivered directly,
by the local actor ref provider.

Aside from providing better performance, this also means that if the hostname
you configure remoting to listen as cannot actually be resolved from within
the very same actor system, such messages will (perhaps counterintuitively)
be delivered just fine.

@@@

## Remote Security

An `ActorSystem` should not be exposed via Akka Remote (Artery) over plain Aeron/UDP or TCP to an untrusted
network (e.g. Internet). It should be protected by network security, such as a firewall. If that is not considered
as enough protection [TLS with mutual authentication](#remote-tls) should be enabled.

Best practice is that Akka remoting nodes should only be accessible from the adjacent network. Note that if TLS is
enabled with mutual authentication there is still a risk that an attacker can gain access to a valid certificate by
compromising any node with certificates issued by the same internal PKI tree.

It is also security best-practice to @ref[disable the Java serializer](serialization.md#disable-java-serializer) because of
its multiple [known attack surfaces](https://community.hpe.com/t5/Security-Research/The-perils-of-Java-deserialization/ba-p/6838995).

<a id="remote-tls"></a>
### Configuring SSL/TLS for Akka Remoting

SSL can be used as the remote transport by using the `tls-tcp` transport:

```
akka.remote.artery {
  transport = tls-tcp
}
```

Next the actual SSL/TLS parameters have to be configured:

```
akka.remote.artery {
  transport = tls-tcp

  ssl.config-ssl-engine {
    key-store = "/example/path/to/mykeystore.jks"
    trust-store = "/example/path/to/mytruststore.jks"

    key-store-password = ${SSL_KEY_STORE_PASSWORD}
    key-password = ${SSL_KEY_PASSWORD}
    trust-store-password = ${SSL_TRUST_STORE_PASSWORD}

    protocol = "TLSv1.2"

    enabled-algorithms = [TLS_DHE_RSA_WITH_AES_128_GCM_SHA256]
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

Mutual authentication between TLS peers is enabled by default. Mutual authentication means that the the passive side
(the TLS server side) of a connection will also request and verify a certificate from the connecting peer.
Without this mode only the client side is requesting and verifying certificates. While Akka is a peer-to-peer
technology, each connection between nodes starts out from one side (the "client") towards the other (the "server").

Note that if TLS is enabled with mutual authentication there is still a risk that an attacker can gain access to a
valid certificate by compromising any node with certificates issued by the same internal PKI tree.

It's recommended that you enable hostname verification with
`akka.remote.artery.ssl.config-ssl-engine.hostname-verification=on`.
When enabled it will verify that the destination hostname matches the hostname in the peer's certificate.

You have a few choices how to set up certificates and hostname verification:

* Have a single set of keys and a single certificate for all nodes and *disable* hostname checking
    * The single set of keys and the single certificate is distributed to all nodes. The certificate can
      be self-signed as it is distributed both as a certificate for authentication but also as the trusted certificate.
    * If the keys/certificate are lost, someone else can connect to your cluster.
    * Adding nodes to the cluster is simple as the key material can be deployed / distributed to the new node.
* Have a single set of keys and a single certificate for all nodes that contains all of the host names and *enable*
  hostname checking.
    * This means that only the hosts mentioned in the certificate can connect to the cluster.
    * It cannot be checked, though, if the node you talk to is actually the node it is supposed to be (or if it is one
      of the other nodes). This seems like a minor restriction as you'll have to trust all cluster nodes the same in an
      Akka cluster anyway.
    * The certificate can be self-signed in which case the same single certificate is distributed and trusted on all
      nodes (but see the next bullet)
    * Adding a new node means that its host name needs to conform to the trusted host names in the certificate.
      That either means to foresee new hosts, use a wildcard certificate, or use a full CA in the first place,
      so you can later issue more certificates if more nodes are to be added (but then you already get into the
      territory of the next solution).
    * If a certificate is stolen, it can only be used to connect to the cluster from a node reachable via a hostname
      that is trusted in the certificate. It would require tampering with DNS to allow other nodes to get access to
      the cluster (however, tampering DNS might be easier in an internal setting than on internet scale).
* Have a CA and then keys/certificates, one for each node, and *enable*  host name checking.
    * Basically like internet HTTPS but that you only trust the internal CA and then issue certificates for each new node.
    * Needs a PKI, the CA certificate is trusted on all nodes, the individual certificates are used for authentication.
    * Only the CA certificate and the key/certificate for a node is distributed.
    * If keys/certificates are stolen, only the same node can access the cluster (unless DNS is tampered with as well).
      You can revoke single certificates.

See also a description of the settings in the @ref:[Remote Configuration](#remote-configuration-artery) section.

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
akka.remote.artery.untrusted-mode = on
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
it should be complemented with @ref:[disabled Java serializer](#disabling-the-java-serializer)
Additional protection can be achieved when running in an untrusted network by
network security (e.g. firewalls).

@@@

Messages sent with actor selection are by default discarded in untrusted mode, but
permission to receive actor selection messages can be granted to specific actors
defined in configuration:

```
akka.remote.artery.trusted-selection-paths = ["/user/receptionist", "/user/namingService"]
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

## Quarantine

Akka remoting is using Aeron as underlying message transport. Aeron is using UDP and adds
among other things reliable delivery and session semantics, very similar to TCP. This means that
the order of the messages are preserved, which is needed for the @ref:[Actor message ordering guarantees](general/message-delivery-reliability.md#message-ordering).
Under normal circumstances all messages will be delivered but there are cases when messages
may not be delivered to the destination:

 * during a network partition and the Aeron session is broken, this automatically recovered once the partition is over
 * when sending too many messages without flow control and thereby filling up the outbound send queue (`outbound-message-queue-size` config)
 * if serialization or deserialization of a message fails (only that message will be dropped)
 * if an unexpected exception occurs in the remoting infrastructure

In short, Actor message delivery is “at-most-once” as described in @ref:[Message Delivery Reliability](general/message-delivery-reliability.md)

Some messages in Akka are called system messages and those cannot be dropped because that would result
in an inconsistent state between the systems. Such messages are used for essentially two features; remote death
watch and remote deployment. These messages are delivered by Akka remoting with “exactly-once” guarantee by
confirming each message and resending unconfirmed messages. If a system message anyway cannot be delivered the
association with the destination system is irrecoverable failed, and Terminated is signaled for all watched
actors on the remote system. It is placed in a so called quarantined state. Quarantine usually does not
happen if remote watch or remote deployment is not used.

Each `ActorSystem` instance has an unique identifier (UID), which is important for differentiating between
incarnations of a system when it is restarted with the same hostname and port. It is the specific
incarnation (UID) that is quarantined. The only way to recover from this state is to restart one of the
actor systems.

Messages that are sent to and received from a quarantined system will be dropped. However, it is possible to
send messages with `actorSelection` to the address of a quarantined system, which is useful to probe if the
system has been restarted.

An association will be quarantined when:

 * Cluster node is removed from the cluster membership.
 * Remote failure detector triggers, i.e. remote watch is used. This is different when @ref:[Akka Cluster](cluster-usage.md)
is used. The unreachable observation by the cluster failure detector can go back to reachable if the network
partition heals. A cluster member is not quarantined when the failure detector triggers.
 * Overflow of the system message delivery buffer, e.g. because of too many `watch` requests at the same time
(`system-message-buffer-size` config).
 * Unexpected exception occurs in the control subchannel of the remoting infrastructure.

The UID of the `ActorSystem` is exchanged in a two-way handshake when the first message is sent to
a destination. The handshake will be retried until the other system replies and no other messages will
pass through until the handshake is completed. If the handshake cannot be established within a timeout
(`handshake-timeout` config) the association is stopped (freeing up resources). Queued messages will be
dropped if the handshake cannot be established. It will not be quarantined, because the UID is unknown.
New handshake attempt will start when next message is sent to the destination.

Handshake requests are actually also sent periodically to be able to establish a working connection
when the destination system has been restarted.

### Watching Remote Actors

Watching a remote actor is API wise not different than watching a local actor, as described in
@ref:[Lifecycle Monitoring aka DeathWatch](actors.md#deathwatch). However, it is important to note, that unlike in the local case, remoting has to handle
when a remote actor does not terminate in a graceful way sending a system message to notify the watcher actor about
the event, but instead being hosted on a system which stopped abruptly (crashed). These situations are handled
by the built-in failure detector.

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

In the @ref:[Remote Configuration](#remote-configuration-artery) you can adjust the `akka.remote.watch-failure-detector.threshold`
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
adjust the @ref:[Remote Configuration](#remote-configuration-artery) of this depending on you environment.
This is how the curve looks like for `acceptable-heartbeat-pause` configured to
3 seconds.

![phi3.png](./images/phi3.png)

## Serialization

When using remoting for actors you must ensure that the `props` and `messages` used for
those actors are serializable. Failing to do so will cause the system to behave in an unintended way.

For more information please see @ref:[Serialization](serialization.md).

<a id="remote-bytebuffer-serialization"></a>
### ByteBuffer based serialization

Artery introduces a new serialization mechanism which allows the `ByteBufferSerializer` to directly write into a
shared `java.nio.ByteBuffer` instead of being forced to allocate and return an `Array[Byte]` for each serialized
message. For high-throughput messaging this API change can yield significant performance benefits, so we recommend
changing your serializers to use this new mechanism.

This new API also plays well with new versions of Google Protocol Buffers and other serialization libraries, which gained
the ability to serialize directly into and from ByteBuffers.

As the new feature only changes how bytes are read and written, and the rest of the serialization infrastructure
remained the same, we recommend reading the @ref:[Serialization](serialization.md) documentation first.

Implementing an `akka.serialization.ByteBufferSerializer` works the same way as any other serializer,

Scala
:  @@snip [Serializer.scala](/akka-actor/src/main/scala/akka/serialization/Serializer.scala) { #ByteBufferSerializer }

Java
:  @@snip [ByteBufferSerializerDocTest.java](/akka-docs/src/test/java/jdocs/actor/ByteBufferSerializerDocTest.java) { #ByteBufferSerializer-interface }

Implementing a serializer for Artery is therefore as simple as implementing this interface, and binding the serializer
as usual (which is explained in @ref:[Serialization](serialization.md)).

Implementations should typically extend `SerializerWithStringManifest` and in addition to the `ByteBuffer` based
`toBinary` and `fromBinary` methods also implement the array based `toBinary` and `fromBinary` methods.
The array based methods will be used when `ByteBuffer` is not used, e.g. in Akka Persistence.

Note that the array based methods can be implemented by delegation like this:

Scala
:  @@snip [ByteBufferSerializerDocSpec.scala](/akka-docs/src/test/scala/docs/actor/ByteBufferSerializerDocSpec.scala) { #bytebufserializer-with-manifest }

Java
:  @@snip [ByteBufferSerializerDocTest.java](/akka-docs/src/test/java/jdocs/actor/ByteBufferSerializerDocTest.java) { #bytebufserializer-with-manifest }

<a id="disable-java-serializer"></a>
### Disabling the Java Serializer

It is highly recommended that you @ref[disable Java serialization](serialization.md#disable-java-serializer).

## Routers with Remote Destinations

It is absolutely feasible to combine remoting with @ref:[Routing](routing.md).

A pool of remote deployed routees can be configured as:

@@snip [RouterDocSpec.scala](/akka-docs/src/test/scala/docs/routing/RouterDocSpec.scala) { #config-remote-round-robin-pool-artery }

This configuration setting will clone the actor defined in the `Props` of the `remotePool` 10
times and deploy it evenly distributed across the two given target nodes.

A group of remote actors can be configured as:

@@snip [RouterDocSpec.scala](/akka-docs/src/test/scala/docs/routing/RouterDocSpec.scala) { #config-remote-round-robin-group-artery }

This configuration setting will send messages to the defined remote actor paths.
It requires that you create the destination actors on the remote nodes with matching paths.
That is not done by the router.

## What is new in Artery

Artery is a reimplementation of the old remoting module aimed at improving performance and stability. It is mostly
source compatible with the old implementation and it is a drop-in replacement in many cases. Main features
of Artery compared to the previous implementation:

 * Based on [Aeron](https://github.com/real-logic/Aeron) (UDP) and Akka Streams TCP/TLS instead of Netty TCP
 * Focused on high-throughput, low-latency communication
 * Isolation of internal control messages from user messages improving stability and reducing false failure detection
in case of heavy traffic by using a dedicated subchannel.
 * Mostly allocation-free operation
 * Support for a separate subchannel for large messages to avoid interference with smaller messages
 * Compression of actor paths on the wire to reduce overhead for smaller messages
 * Support for faster serialization/deserialization using ByteBuffers directly
 * Built-in Flight-Recorder to help debugging implementation issues without polluting users logs with implementation
specific events
 * Providing protocol stability across major Akka versions to support rolling updates of large-scale systems

The main incompatible change from the previous implementation that the protocol field of the string representation of an
`ActorRef` is always *akka* instead of the previously used *akka.tcp* or *akka.ssl.tcp*. Configuration properties
are also different.


## Performance tuning

### Lanes

Message serialization and deserialization can be a bottleneck for remote communication. Therefore there is support for parallel inbound and outbound lanes to perform serialization and other tasks for different destination actors in parallel. Using multiple lanes is of most value for the inbound messages, since all inbound messages from all remote systems share the same inbound stream. For outbound messages there is already one stream per remote destination system, so multiple outbound lanes only add value when sending to different actors in same destination system.

The selection of lane is based on consistent hashing of the recipient ActorRef to preserve message ordering per receiver.

Note that lowest latency can be achieved with `inbound-lanes=1` and `outbound-lanes=1` because multiple lanes introduce an asynchronous boundary. 

Also note that the total amount of parallel tasks are bound by the `remote-dispatcher` and the thread pool size should not exceed the number of CPU cores minus headroom for actually processing the messages in the application, i.e. in practice the the pool size should be less than half of the number of cores.

See `inbound-lanes` and `outbound-lanes` in the @ref:[reference configuration](general/configuration.md#config-akka-remote-artery) for default values.

### Dedicated subchannel for large messages

All the communication between user defined remote actors are isolated from the channel of Akka internal messages so
a large user message cannot block an urgent system message. While this provides good isolation for Akka services, all
user communications by default happen through a shared network connection (an Aeron stream). When some actors
send large messages this can cause other messages to suffer higher latency as they need to wait until the full
message has been transported on the shared channel (and hence, shared bottleneck). In these cases it is usually
helpful to separate actors that have different QoS requirements: large messages vs. low latency.

Akka remoting provides a dedicated channel for large messages if configured. Since actor message ordering must
not be violated the channel is actually dedicated for *actors* instead of messages, to ensure all of the messages
arrive in send order. It is possible to assign actors on given paths to use this dedicated channel by using
path patterns that have to be specified in the actor system's configuration on both the sending and the receiving side:

```
akka.remote.artery.large-message-destinations = [
   "/user/largeMessageActor",
   "/user/largeMessagesGroup/*",
   "/user/anotherGroup/*/largeMesssages",
   "/user/thirdGroup/**",
]
```

This means that all messages sent to the following actors will pass through the dedicated, large messages channel:

 * `/user/largeMessageActor`
 * `/user/largeMessageActorGroup/actor1`
 * `/user/largeMessageActorGroup/actor2`
 * `/user/anotherGroup/actor1/largeMessages`
 * `/user/anotherGroup/actor2/largeMessages`
 * `/user/thirdGroup/actor3/`
 * `/user/thirdGroup/actor4/actor5`

Messages destined for actors not matching any of these patterns are sent using the default channel as before.

### External, shared Aeron media driver

The Aeron transport is running in a so called [media driver](https://github.com/real-logic/Aeron/wiki/Media-Driver-Operation).
By default, Akka starts the media driver embedded in the same JVM process as application. This is
convenient and simplifies operational concerns by only having one process to start and monitor.

The media driver may use rather much CPU resources. If you run more than one Akka application JVM on the
same machine it can therefore be wise to share the media driver by running it as a separate process.

The media driver has also different resource usage characteristics than a normal application and it can
therefore be more efficient and stable to run the media driver as a separate process.

Given that Aeron jar files are in the classpath the standalone media driver can be started with:

```
java io.aeron.driver.MediaDriver
```

The needed classpath:

```
Agrona-0.5.4.jar:aeron-driver-1.0.1.jar:aeron-client-1.0.1.jar
```

You find those jar files on [Maven Central](http://search.maven.org/), or you can create a
package with your preferred build tool.

You can pass [Aeron properties](https://github.com/real-logic/Aeron/wiki/Configuration-Options) as
command line *-D* system properties:

```
-Daeron.dir=/dev/shm/aeron
```

You can also define Aeron properties in a file:

```
java io.aeron.driver.MediaDriver config/aeron.properties
```

An example of such a properties file:

```
aeron.mtu.length=16384
aeron.socket.so_sndbuf=2097152
aeron.socket.so_rcvbuf=2097152
aeron.rcv.buffer.length=16384
aeron.rcv.initial.window.length=2097152
agrona.disable.bounds.checks=true

aeron.threading.mode=SHARED_NETWORK

# low latency settings
#aeron.threading.mode=DEDICATED
#aeron.sender.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy
#aeron.receiver.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy

# use same director in akka.remote.artery.advanced.aeron-dir config
# of the Akka application
aeron.dir=/dev/shm/aeron
```

Read more about the media driver in the [Aeron documentation](https://github.com/real-logic/Aeron/wiki/Media-Driver-Operation).

To use the external media driver from the Akka application you need to define the following two
configuration properties:

```
akka.remote.artery.advanced {
  embedded-media-driver = off
  aeron-dir = /dev/shm/aeron
}
```

The `aeron-dir` must match the directory you started the media driver with, i.e. the `aeron.dir` property.

Several Akka applications can then be configured to use the same media driver by pointing to the
same directory.

Note that if the media driver process is stopped the Akka applications that are using it will also be stopped.

### Aeron Tuning

See Aeron documentation about [Performance Testing](https://github.com/real-logic/Aeron/wiki/Performance-Testing).

### Fine-tuning CPU usage latency tradeoff

Artery has been designed for low latency and as a result it can be CPU hungry when the system is mostly idle.
This is not always desirable. When using the Aeron transport it is possible to tune the tradeoff between CPU
usage and latency with the following configuration:

```
# Values can be from 1 to 10, where 10 strongly prefers low latency
# and 1 strongly prefers less CPU usage
akka.remote.artery.advanced.idle-cpu-level = 1
```

By setting this value to a lower number, it tells Akka to do longer "sleeping" periods on its thread dedicated
for [spin-waiting](https://en.wikipedia.org/wiki/Busy_waiting) and hence reducing CPU load when there is no
immediate task to execute at the cost of a longer reaction time to an event when it actually happens. It is worth
to be noted though that during a continuously high-throughput period this setting makes not much difference
as the thread mostly has tasks to execute. This also means that under high throughput (but below maximum capacity)
the system might have less latency than at low message rates.

## Internal Event Log for Debugging (Flight Recorder)

@@@ note

In this version ($akka.version$) the flight-recorder is disabled by default because there is no automatic
file name and path calculation implemented to make it possible to reuse the same file for every restart of
the same actor system without clashing with files produced by other systems (possibly running on the same machine).
Currently, you have to set the path and file names yourself to avoid creating an unbounded number
of files and enable flight recorder manually by adding *akka.remote.artery.advanced.flight-recorder.enabled=on* to
your configuration file. This a limitation of the current version and will not be necessary in the future.

@@@

Emitting event information (logs) from internals is always a trade off. The events that are usable for
the Akka developers are usually too low level to be of any use for users and usually need to be fine-grained enough
to provide enough information to be able to debug issues in the internal implementation. This usually means that
these logs are hidden behind special flags and emitted at low log levels to not clutter the log output of the user
system. Unfortunately this means that during production or integration testing these flags are usually off and
events are not available when an actual failure happens - leaving maintainers in the dark about details of the event.
To solve this contradiction, remoting has an internal, high-performance event store for debug events which is always on.
This log and the events that it contains are highly specialized and not directly exposed to users, their primary purpose
is to help the maintainers of Akka to identify and solve issues discovered during daily usage. When you encounter
production issues involving remoting, you can include the flight recorder log file in your bug report to give us
more insight into the nature of the failure.

There are various important features of this event log:

 * Flight Recorder produces a fixed size file completely encapsulating log rotation. This means that this
file will never grow in size and will not cause any unexpected disk space shortage in production.
 * This file is crash resistant, i.e. its contents can be recovered even if the JVM hosting the `ActorSystem`
crashes unexpectedly.
 * Very low overhead, specialized, binary logging that has no significant overhead and can be safely left enabled
for production systems.

The location of the file can be controlled via the *akka.remote.artery.advanced.flight-recorder.destination* setting (see
@ref:[akka-remote (artery)](general/configuration.md#config-akka-remote-artery) for details). By default, a file with the *.afr* extension is produced in the temporary
directory of the operating system. In cases where the flight recorder casuses issues, it can be disabled by adding the
setting *akka.remote.artery.advanced.flight-recorder.enabled=off*, although this is not recommended.

<a id="remote-configuration-artery"></a>
## Remote Configuration

There are lots of configuration properties that are related to remoting in Akka. We refer to the
@ref:[reference configuration](general/configuration.md#config-akka-remote-artery) for more information.

@@@ note

Setting properties like the listening IP and port number programmatically is
best done by using something like the following:

@@snip [RemoteDeploymentDocTest.java](/akka-docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #programmatic-artery }

@@@

<a id="remote-configuration-nat-artery"></a>
### Akka behind NAT or in a Docker container

In setups involving Network Address Translation (NAT), Load Balancers or Docker
containers the hostname and port pair that Akka binds to will be different than the "logical"
host name and port pair that is used to connect to the system from the outside. This requires
special configuration that sets both the logical and the bind pairs for remoting.

```
akka {
  remote {
    artery {
      canonical.hostname = my.domain.com      # external (logical) hostname
      canonical.port = 8000                   # external (logical) port

      bind.hostname = local.address # internal (bind) hostname
      bind.port = 25520              # internal (bind) port
    }
 }
}
```

You can look at the
@java[@extref[Cluster with docker-compse example project](samples:akka-sample-cluster-docker-compose-java)]
@scala[@extref[Cluster with docker-compose example project](samples:akka-sample-cluster-docker-compose-scala)]
to see what this looks like in practice.
