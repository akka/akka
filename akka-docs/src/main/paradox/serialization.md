# Serialization

## Dependency

To use Serialization, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction

The messages that Akka actors send to each other are JVM objects @scala[(e.g. instances of Scala case classes)]. Message passing between actors that live on the same JVM is straightforward. It is done via reference passing. However, messages that have to escape the JVM to reach an actor running on a different host have to undergo some form of serialization (i.e. the objects have to be converted to and from byte arrays).

The serialization mechanism in Akka allows you to write custom serializers and to define which serializer to use for what.

@ref:[Serialization with Jackson](serialization-jackson.md) is a good choice in many cases and our
recommendation if you don't have other preference.

[Google Protocol Buffers](https://developers.google.com/protocol-buffers/) is good if you want
more control over the schema evolution of your messages, but it requires more work to develop and
maintain the mapping between serialized representation and domain representation.

Akka itself uses Protocol Buffers to serialize internal messages (for example cluster gossip messages).

## Usage

### Configuration

For Akka to know which `Serializer` to use for what, you need edit your configuration,
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
met, because e.g. two marker interfaces that have been configured for serialization
both apply and neither is a subtype of the other, a warning will be issued.

@@@ note

If @java[you are using Scala for your message protocol and] your messages are contained inside of a Scala object, then in order to
reference those messages, you will need to use the fully qualified Java class name. For a message
named `Message` contained inside the @java[Scala] object named `Wrapper`
you would need to reference it as `Wrapper$Message` instead of `Wrapper.Message`.

@@@

Akka provides serializers for several primitive types and [protobuf](http://code.google.com/p/protobuf/)
`com.google.protobuf.GeneratedMessage` (protobuf2) and `com.google.protobuf.GeneratedMessageV3` (protobuf3) by default (the latter only if
depending on the akka-remote module), so normally you don't need to add
configuration for that if you send raw protobuf messages as actor messages.

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

The manifest is a type hint so that the same serializer can be used for different classes.

Note that when deserializing from bytes the manifest and the identifier of the serializer are needed.
It is important to use the serializer identifier in this way to support rolling updates, where the
`serialization-bindings` for a class may have changed from one serializer to another. Therefore the three parts
consisting of the bytes, the serializer id, and the manifest should always be transferred or stored together so that
they can be deserialized with different `serialization-bindings` configuration.

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

Then you only need to fill in the blanks, bind it to a name in your configuration and then
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

You must also bind it to a name in your configuration and then list which classes
that should be serialized using it.

It's recommended to throw `IllegalArgumentException` or ``java.io.NotSerializableException` in
`fromBinary` if the manifest is unknown. This makes it possible to introduce new message types and
send them to nodes that don't know about them. This is typically needed when performing
rolling upgrades, i.e. running a cluster with mixed versions for while.
Those exceptions are treated as a transient problem in the classic remoting
layer. The problem will be logged and message is dropped. Other exceptions will tear down
the TCP connection because it can be an indication of corrupt bytes from the underlying
transport. Artery TCP handles all deserialization exceptions as transient problems.

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
transport address.

Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #external-address-default }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #external-address-default }

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

There is also a default remote address which is the one used by cluster support
(and typical systems have just this one); you can get it like this:

Scala
:  @@snip [SerializationDocSpec.scala](/akka-docs/src/test/scala/docs/serialization/SerializationDocSpec.scala) { #external-address-default }

Java
:  @@snip [SerializationDocTest.java](/akka-docs/src/test/java/jdocs/serialization/SerializationDocTest.java) { #external-address-default }

### Deep serialization of Actors

The recommended approach to do deep serialization of internal actor state is to use Akka @ref:[Persistence](persistence.md).

## Serialization of Akka's messages

Akka is using a Protobuf 3 for serialization of messages defined by Akka. This dependency is
shaded in the `akka-protobuf-v3` artifact so that applications can use another version of Protobuf.

Applications should use standard Protobuf dependency and not `akka-protobuf-v3`.

## Java serialization

Java serialization is known to be slow and [prone to attacks](https://community.hpe.com/t5/Security-Research/The-perils-of-Java-deserialization/ba-p/6838995)
of various kinds - it never was designed for high throughput messaging after all.
One may think that network bandwidth and latency limit the performance of remote messaging, but serialization is a more typical bottleneck.

@@@ note

Akka serialization with Java serialization is disabled by default and Akka itself doesn't use Java serialization
for any of its internal messages. It is highly discouraged to enable Java serialization in production.

The log messages emitted by the disabled Java serializer in production SHOULD be treated as potential
attacks which the serializer prevented, as they MAY indicate an external operator
attempting to send malicious messages intending to use java serialization as attack vector.
The attempts are logged with the SECURITY marker.

@@@

However, for early prototyping it is very convenient to use. For that reason and for compatibility with
older systems that rely on Java serialization it can be enabled with the following configuration:

```ruby
akka.actor.allow-java-serialization = on
```

Akka will still log warning when Java serialization is used and to silent that you may add:

```ruby
akka.actor.warn-about-java-serializer-usage = off
```

### Java serialization compatibility

It is not safe to mix major Scala versions when using the Java serialization as Scala does not guarantee compatibility
and this could lead to very surprising errors.

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

* [Akka-quickser by Roman Levenstein](https://github.com/romix/akka-quickser-serialization)

* [Akka-kryo by Roman Levenstein](https://github.com/romix/akka-kryo-serialization)

* [Twitter Chill Scala extensions for Kryo (based on Akka Version 2.3.x but due to backwards compatibility of the Serializer Interface this extension also works with 2.4.x)](https://github.com/twitter/chill)
