# Serialization

## Dependency

To use Serialization, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction

The messages that Akka actors send to each other are JVM objects (e.g. instances of Scala case classes). Message passing between actors that live on the same JVM is straightforward. It is done via reference passing. However, messages that have to escape the JVM to reach an actor running on a different host have to undergo some form of serialization (i.e. the objects have to be converted to and from byte arrays).

Akka itself uses Protocol Buffers to serialize internal messages (i.e. cluster gossip messages). However, the serialization mechanism in Akka allows you to write custom serializers and to define which serializer to use for what.

## Usage

### Configuration

For Akka to know which `Serializer` to use for what, you need edit your [Configuration](),
in the "akka.actor.serializers"-section you bind names to implementations of the `akka.serialization.Serializer`
you wish to use, like this:

@@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #serialize-serializers-config }

After you've bound names to different implementations of `Serializer` you need to wire which classes
should be serialized using which `Serializer`, this is done in the "akka.actor.serialization-bindings"-section:

@@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #serialization-bindings-config }

You only need to specify the name of an interface or abstract base class of the
messages. In case of ambiguity, i.e. the message implements several of the
configured classes, the most specific configured class will be used, i.e. the
one of which all other candidates are superclasses. If this condition cannot be
met, because e.g. `java.io.Serializable` and `MyOwnSerializable` both apply
and neither is a subtype of the other, a warning will be issued.

@@@ note

If @java[you are using Scala for your message protocol and] your messages are contained inside of a Scala object, then in order to
reference those messages, you will need to use the fully qualified Java class name. For a message
named `Message` contained inside the @java[Scala] object named `Wrapper`
you would need to reference it as `Wrapper$Message` instead of `Wrapper.Message`.

@@@

Akka provides serializers for `java.io.Serializable` and [protobuf](http://code.google.com/p/protobuf/)
`com.google.protobuf.GeneratedMessage` by default (the latter only if
depending on the akka-remote module), so normally you don't need to add
configuration for that; since `com.google.protobuf.GeneratedMessage`
implements `java.io.Serializable`, protobuf messages will always be
serialized using the protobuf protocol unless specifically overridden. In order
to disable a default serializer, see @ref:[Disabling the Java Serializer](remoting-artery.md#disable-java-serializer)

### Enable additional bindings

A few types in Akka are, for backwards-compatibility reasons, still serialized by using Java serializer by default.
You can switch them to using protocol buffers instead by adding the following bindings or set `akka.actor.allow-java-serialization=off`, which will make them serialized using protocol buffers instead.
Refer to @ref[Rolling Upgrades](#rolling-upgrades) to understand how it is possible to turn and start using these new
serializers in your clustered applications.

You can enable them one by one adding by adding their bindings to the misc serializer, like this:

```
akka.actor.serialization-bindings {
    "akka.Done"                 = akka-misc
    "akka.NotUsed"              = akka-misc
    "akka.actor.Address"        = akka-misc
    "akka.remote.UniqueAddress" = akka-misc
}
```

Alternatively, you can disable all Java serialization which then automatically will add the `java-serialization-disabled-additional-serialization-bindings` bindings to the active bindings.

### Verification

Normally, messages sent between local actors (i.e. same JVM) do not undergo serialization. For testing, sometimes, it may be desirable to force serialization on all messages (both remote and local). If you want to do this in order to verify that your messages are serializable you can enable the following config option:

@@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #serialize-messages-config }

If you want to verify that your `Props` are serializable you can enable the following config option:

@@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #serialize-creators-config }

@@@ warning

We recommend having these config options turned on **only** when you're running tests. Turning these options on in production is pointless, as it would negatively impact the performance of local message passing without giving any gain.

@@@

### Programmatic

If you want to programmatically serialize/deserialize using Akka Serialization,
here's some examples:

Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #imports }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #imports }


Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #programmatic }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #programmatic }

For more information, have a look at the `ScalaDoc` for `akka.serialization._`

## Customization

The first code snippet on this page contains a configuration file that references a custom serializer `docs.serialization.MyOwnSerializer`. How would we go about creating such a custom serializer?

### Creating new Serializers

A custom `Serializer` has to inherit from @scala[`akka.serialization.Serializer`]@java[`akka.serialization.JSerializer`] and can be defined like the following:

Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #imports }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #imports }


Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #my-own-serializer }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #my-own-serializer }

The manifest is a type hint so that the same serializer can be used for different
classes. The manifest parameter in @scala[`fromBinary`]@java[`fromBinaryJava`] is the class of the object that
was serialized. In `fromBinary` you can match on the class and deserialize the
bytes to different objects.

Then you only need to fill in the blanks, bind it to a name in your [Configuration]() and then
list which classes that should be serialized using it.

<a id="string-manifest-serializer"></a>
### Serializer with String Manifest

The `Serializer` illustrated above supports a class based manifest (type hint).
For serialization of data that need to evolve over time the `SerializerWithStringManifest`
is recommended instead of `Serializer` because the manifest (type hint) is a `String`
instead of a `Class`. That means that the class can be moved/removed and the serializer
can still deserialize old data by matching  on the `String`. This is especially useful
for @ref:[Persistence](persistence.md).

The manifest string can also encode a version number that can be used in `fromBinary` to
deserialize in different ways to migrate old data to new domain objects.

If the data was originally serialized with `Serializer` and in a later version of the
system you change to `SerializerWithStringManifest` the manifest string will be the full
class name if you used `includeManifest=true`, otherwise it will be the empty string.

This is how a `SerializerWithStringManifest` looks like:

Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #my-own-serializer2 }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #my-own-serializer2 }

You must also bind it to a name in your [Configuration]() and then list which classes
that should be serialized using it.

It's recommended to throw `java.io.NotSerializableException` in `fromBinary`
if the manifest is unknown. This makes it possible to introduce new message types and
send them to nodes that don't know about them. This is typically needed when performing
rolling upgrades, i.e. running a cluster with mixed versions for while.
`NotSerializableException` is treated as a transient problem in the TCP based remoting
layer. The problem will be logged and message is dropped. Other exceptions will tear down
the TCP connection because it can be an indication of corrupt bytes from the underlying
transport.

### Serializing ActorRefs

All ActorRefs are serializable using JavaSerializer, but in case you are writing your
own serializer, you might want to know how to serialize and deserialize them properly.
In the general case, the local address to be used depends on the type of remote
address which shall be the recipient of the serialized information. Use
`Serialization.serializedActorPath(actorRef)` like this:

Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #imports }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #imports }


Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #actorref-serializer }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #actorref-serializer }

This assumes that serialization happens in the context of sending a message
through the remote transport. There are other uses of serialization, though,
e.g. storing actor references outside of an actor application (database, etc.).
In this case, it is important to keep in mind that the
address part of an actor’s path determines how that actor is communicated with.
Storing a local actor path might be the right choice if the retrieval happens
in the same logical context, but it is not enough when deserializing it on a
different network host: for that it would need to include the system’s remote
transport address. An actor system is not limited to having just one remote
transport per se, which makes this question a bit more interesting. To find out
the appropriate address to use when sending to `remoteAddr` you can use
`ActorRefProvider.getExternalAddressFor(remoteAddr)` like this:

Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #external-address }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #external-address }

@@@ note

`ActorPath.toSerializationFormatWithAddress` differs from `toString` if the
address does not already have `host` and `port` components, i.e. it only
inserts address information for local addresses.

`toSerializationFormatWithAddress` also adds the unique id of the actor, which will
change when the actor is stopped and then created again with the same name.
Sending messages to a reference pointing the old actor will not be delivered
to the new actor. If you don't want this behavior, e.g. in case of long term
storage of the reference, you can use `toStringWithAddress`, which doesn't
include the unique id.

@@@

This requires that you know at least which type of address will be supported by
the system which will deserialize the resulting actor reference; if you have no
concrete address handy you can create a dummy one for the right protocol using
@scala[`Address(protocol, "", "", 0)`]@java[`new Address(protocol, "", "", 0)`] (assuming that the actual transport used is as
lenient as Akka’s RemoteActorRefProvider).

There is also a default remote address which is the one used by cluster support
(and typical systems have just this one); you can get it like this:

Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #external-address-default }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #external-address-default }

Another solution is to encapsulate your serialization code in `Serialization.withTransportInformation`. 
It ensures the actorRefs are serialized using systems default address when 
no other address is available.

### Deep serialization of Actors

The recommended approach to do deep serialization of internal actor state is to use Akka @ref:[Persistence](persistence.md).

<a id="disable-java-serializer"></a>
## Disabling the Java Serializer

Since the `2.4.11` release of Akka it is possible to entirely disable the default Java Serialization mechanism.
For compatibility reasons, the current (non-Artery) @ref:[Remoting](remoting.md) still uses Java
serialization for some classes, however you can disable it in this remoting implementation as well by following
the steps below.

The first step is to enable some additional serializers that replace previous Java serialization of some internal
messages. This is recommended also when you can't disable Java serialization completely. Those serializers are
enabled with this configuration:

```ruby
akka.actor {
  # Set this to on to enable serialization-bindings defined in
  # additional-serialization-bindings. Those are by default not included
  # for backwards compatibility reasons. They are enabled by default if
  # akka.remote.artery.enabled=on.
  enable-additional-serialization-bindings = on
}
```

The reason these are not enabled by default is wire-level compatibility between any 2.4.x Actor Systems.
If you roll out a new cluster, all on the same Akka version that can enable these serializers it is recommended to
enable this setting. When using @ref:[Remoting (codename Artery)](remoting-artery.md) these serializers are enabled by default.

@@@ warning

Please note that when enabling the `additional-serialization-bindings` when using the old remoting,
you must do so on all nodes participating in a cluster, otherwise the mis-aligned serialization
configurations will cause deserialization errors on the receiving nodes.

@@@

Java serialization is known to be slow and [prone to attacks](https://community.hpe.com/t5/Security-Research/The-perils-of-Java-deserialization/ba-p/6838995)
of various kinds - it never was designed for high throughput messaging after all. However, it is very
convenient to use, thus it remained the default serialization mechanism that Akka used to
serialize user messages as well as some of its internal messages in previous versions.
Since the release of Artery, Akka internals do not rely on Java serialization anymore (one exception being `java.lang.Throwable`).

@@@ warning

Please note Akka 2.5 by default does not use any Java Serialization for its own internal messages, unlike 2.4 where
by default it still did for a few of the messages. If you want an 2.4.x system to communicate with a 2.5.x series, for
example during a rolling deployment you should first enable `additional-serialization-bindings` on the old systems.
You must do so on all nodes participating in a cluster, otherwise the mis-aligned serialization
configurations will cause deserialization errors on the receiving nodes. These additional serialization bindings are
enabled by default in Akka 2.5.x.

@@@

@@@ note

When using the new remoting implementation (codename Artery), Akka does not use Java Serialization for any of its internal messages.
It is highly encouraged to disable java serialization, so please plan to do so at the earliest possibility you have in your project.

One may think that network bandwidth and latency limit the performance of remote messaging, but serialization is a more typical bottleneck.

@@@

For user messages, the default serializer, implemented using Java serialization, remains available and enabled.
We do however recommend to disable it entirely and utilise a proper serialization library instead in order effectively utilise
the improved performance and ability for rolling deployments using Artery. Libraries that we recommend to use include,
but are not limited to, [Kryo](https://github.com/EsotericSoftware/kryo) by using the [akka-kryo-serialization](https://github.com/romix/akka-kryo-serialization) library or [Google Protocol Buffers](https://developers.google.com/protocol-buffers/) if you want
more control over the schema evolution of your messages.

In order to completely disable Java Serialization in your Actor system you need to add the following configuration to
your `application.conf`:

```ruby
akka.actor.allow-java-serialization = off
```

This will completely disable the use of `akka.serialization.JavaSerialization` by the
Akka Serialization extension, instead `DisabledJavaSerializer` will
be inserted which will fail explicitly if attempts to use java serialization are made.

It will also enable the above mentioned `enable-additional-serialization-bindings`.

The log messages emitted by such serializer SHOULD be treated as potential
attacks which the serializer prevented, as they MAY indicate an external operator
attempting to send malicious messages intending to use java serialization as attack vector.
The attempts are logged with the SECURITY marker.

Please note that this option does not stop you from manually invoking java serialization.

## Serialization compatibility

It is not safe to mix major Scala versions when using the Java serialization as Scala does not guarantee compatibility
and this could lead to very surprising errors.

If using the Akka Protobuf serializers (implicitly with `akka.actor.allow-java-serialization = off` or explicitly with
`enable-additional-serialization-bindings = true`) for the internal Akka messages those will not require the same major
Scala version however you must also ensure the serializers used for your own types does not introduce the same
incompatibility as Java serialization does.

## Rolling upgrades

A serialized remote message (or persistent event) consists of serializer-id, the manifest, and the binary payload.
When deserializing it is only looking at the serializer-id to pick which `Serializer` to use for `fromBinary`.
The message class (the bindings) is not used for deserialization. The manifest is only used within the
`Serializer` to decide how to deserialize the payload, so one `Serializer` can handle many classes.

That means that it is possible to change serialization for a message by performing two rolling upgrade steps to
switch to the new serializer.

1. Add the `Serializer` class and define it in `akka.actor.serializers` config section, but not in
  `akka.actor.serialization-bindings`. Perform a rolling upgrade for this change. This means that the
  serializer class exists on all nodes and is registered, but it is still not used for serializing any
  messages. That is important because during the rolling upgrade the old nodes still don't know about
  the new serializer and would not be able to deserialize messages with that format.

1. The second change is to register that the serializer is to be used for certain classes by defining
   those in the `akka.actor.serialization-bindings` config section. Perform a rolling upgrade for this
   change. This means that new nodes will use the new serializer when sending messages and old nodes will
   be able to deserialize the new format. Old nodes will continue to use the old serializer when sending
   messages and new nodes will be able to deserialize the old format.

As an optional third step the old serializer can be completely removed if it was not used for persistent events.
It must still be possible to deserialize the events that were stored with the old serializer.

## External Akka Serializers

[Akka-quickser by Roman Levenstein](https://github.com/romix/akka-quickser-serialization)

[Akka-kryo by Roman Levenstein](https://github.com/romix/akka-kryo-serialization)

[Twitter Chill Scala extensions for Kryo (based on Akka Version 2.3.x but due to backwards compatibility of the Serializer Interface this extension also works with 2.4.x)](https://github.com/twitter/chill)
