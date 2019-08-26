# Serialization with Jackson

## Dependency

To use Jackson Serialization, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-serialization-jackson_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction

You find general concepts for for Akka serialization in the @ref:[Serialization](serialization.md) section.
This section describes how to use the Jackson serializer for application specific messages and persistent
events and snapshots.

[Jackson](https://github.com/FasterXML/jackson) has support for both text based JSON and
binary formats.

In many cases ordinary classes can be serialized by Jackson without any additional hints, but sometimes
annotations are needed to specify how to convert the objects to JSON/bytes.

## Usage

To enable Jackson serialization for a class you need to configure it or one of its super classes
in serialization-bindings configuration. Typically you will create a marker @scala[trait]@java[interface]
for that purpose and let the messages @scala[extend]@java[implement] that.

Scala
:  @@snip [SerializationDocSpec.scala](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/SerializationDocSpec.scala) { #marker-interface }

Java
:  @@snip [MySerializable.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/MySerializable.java) { #marker-interface }

Then you configure the class name of the marker @scala[trait]@java[interface] in `serialization-bindings` to
one of the supported Jackson formats: `jackson-json` or `jackson-cbor`

@@snip [config](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/SerializationDocSpec.scala) { #serialization-bindings }

A good convention would be to name the marker interface `CborSerializable` or `JsonSerializable`.
In this documentation we have used `MySerializable` to make it clear that the marker interface itself is not
provided by Akka.

That is all that is needed for basic classes where Jackson understands the structure. A few cases that requires
annotations are described below.

Note that it's only the top level class or its marker @scala[trait]@java[interface] that must be defined in
`serialization-bindings`, not nested classes that it references in member fields.

@@@ note

Add the `-parameters` Java compiler option for usage by the [ParameterNamesModule](https://github.com/FasterXML/jackson-modules-java8/tree/master/parameter-names).
It reduces the need for some annotations.

@@@

## Security

For security reasons it is disallowed to bind the Jackson serializers to
open ended types that might be a target for [serialization gadgets](https://medium.com/@cowtowncoder/on-jackson-cves-dont-panic-here-is-what-you-need-to-know-54cd0d6e8062),
such as:

* `java.lang.Object`
* `java.io.Serializable`
* `java.util.Comparable`.

The blacklist of possible serialization gadget classes defined by Jackson databind are checked
and disallowed for deserialization.

@@@ warning

Don't use `@JsonTypeInfo(use = Id.CLASS)` or `ObjectMapper.enableDefaultTyping` since that is a security risk
when using @ref:[polymorphic types](#polymorphic-types).

@@@

### Formats

The following formats are supported, and you select which one to use in the `serialization-bindings`
configuration as described above.

* `jackson-json` - ordinary text based JSON
* `jackson-cbor` - binary [CBOR data format](https://github.com/FasterXML/jackson-dataformats-binary/tree/master/cbor)

The binary format is more compact, with slightly better performance than the JSON format.

## Annotations

@@@ div {.group-java}

### Constructor with single parameter

You might run into an exception like this:

```
MismatchedInputException: Cannot construct instance of `...` (although at least one Creator exists): cannot deserialize from Object value (no delegate- or property-based Creator)
```

That is probably because the class has a constructor with a single parameter, like:

Java
:  @@snip [SerializationDocTest.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/SerializationDocTest.java) { #one-constructor-param-1 }

That can be solved by adding `@JsonCreator` or `@JsonProperty` annotations:

Java
:  @@snip [SerializationDocTest.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/SerializationDocTest.java) { #one-constructor-param-2 }

or

Java
:  @@snip [SerializationDocTest.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/SerializationDocTest.java) { #one-constructor-param-3 }


The `ParameterNamesModule` is configured with `JsonCreator.Mode.PROPERTIES` as described in the
[Jackson documentation](https://github.com/FasterXML/jackson-modules-java8/tree/master/parameter-names#delegating-creator)

@@@

## Polymorphic types

A polymorphic type is when a certain base type has multiple alternative implementations. When nested fields or
collections are of polymorphic type the concrete implementations of the type must be listed with `@JsonTypeInfo`
and `@JsonSubTypes` annotations.

Example:

Scala
:  @@snip [SerializationDocSpec.scala](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/SerializationDocSpec.scala) { #polymorphism }

Java
:  @@snip [SerializationDocTest.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/SerializationDocTest.java) { #polymorphism }

If you haven't defined the annotations you will see an exception like this:

```
InvalidDefinitionException: Cannot construct instance of `...` (no Creators, like default construct, exist): abstract types either need to be mapped to concrete types, have custom deserializer, or contain additional type information
```

Note that this is not needed for a top level class, but for fields inside it. In this example `Animal` is
used inside of `Zoo`, which is sent as a message or persisted. If `Animal` was sent or persisted standalone
the annotations are not needed because then it is the concrete subclasses `Lion` or `Elephant` that are
serialized.

When specifying allowed subclasses with those annotations the class names will not be included in the serialized
representation and that is important for @ref:[preventing loading of malicious serialization gadgets](#security)
when deserializing.

@@@ warning

Don't use `@JsonTypeInfo(use = Id.CLASS)` or `ObjectMapper.enableDefaultTyping` since that is a security risk
when using polymorphic types.

@@@

@@@ div {.group-scala}

### ADT with trait and case object

In Scala it's common to use a sealed trait and case objects to represent enums. If the values are case classes
the `@JsonSubTypes` annotation as described above works, but if the values are case objects it will not.
The annotation requires a `Class` and there is no way to define that in an annotation for a `case object`.

This can be solved by implementing a custom serialization for the enums. Annotate the `trait` with
`@JsonSerialize` and `@JsonDeserialize` and implement the serialization with `StdSerializer` and
`StdDeserializer`.

Scala
:  @@snip [CustomAdtSerializer.scala](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/CustomAdtSerializer.scala) { #adt-trait-object }


@@@

## Jackson Modules

The following Jackson modules are enabled by default:

@@snip [reference.conf](/akka-serialization-jackson/src/main/resources/reference.conf) { #jackson-modules }

You can amend the configuration `akka.serialization.jackson.jackson-modules` to enable other modules.

The [ParameterNamesModule](https://github.com/FasterXML/jackson-modules-java8/tree/master/parameter-names) requires that the `-parameters`
Java compiler option is enabled.

### Compression

JSON can be rather verbose and for large messages it can be beneficial compress large payloads. Messages larger
than the following configuration are compressed with GZIP.

@@snip [reference.conf](/akka-serialization-jackson/src/main/resources/reference.conf) { #compression }

Compression can be disabled by setting this configuration property to `off`. It will still be able to decompress
payloads that were compressed when serialized, e.g. if this configuration is changed.

## Additional configuration

### Configuration per binding

By default the configuration for the Jackson serializers and their `ObjectMapper`s is defined in
the `akka.serialization.jackson` section. It is possible to override that configuration in a more
specific `akka.serialization.jackson.<binding name>` section.

@@snip [config](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/SerializationDocSpec.scala) { #specific-config }

It's also possible to define several bindings and use different configuration for them. For example,
different settings for remote messages and persisted events.

@@snip [config](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/SerializationDocSpec.scala) { #several-config }

## Additional features

Additional Jackson serialization features can be enabled/disabled in configuration. The default values from
Jackson are used aside from the the following that are changed in Akka's default configuration.

@@snip [reference.conf](/akka-serialization-jackson/src/main/resources/reference.conf) { #features }

### Date/time format

`WRITE_DATES_AS_TIMESTAMPS` is by default disabled, which means that date/time fields are serialized in
ISO-8601 (rfc3339) `yyyy-MM-dd'T'HH:mm:ss.SSSZ` format instead of numeric arrays. This is better for
interoperability but it is slower. If you don't need the ISO format for interoperability with external systems
you can change the following configuration for better performance of date/time fields.

@@snip [config](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/SerializationDocSpec.scala) { #date-time }

Jackson is still be able to deserialize the other format independent of this setting.
