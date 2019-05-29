# Serialization with Jackson

## Dependency

To use Serialization, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-serialization-jackson_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction

You find general concepts for for Akka serialization in the @ref:[Serialization](serialization.md) section.
This section describes how to use the Jackson serializer for application specific messages and persistent
event and snapshots.

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

Then you configure the class name of marker @scala[trait]@java[interface] in `serialization-bindings` to
one of the supported Jackson formats: `jackson-json`, `jackson-cbor` or `jackson-smile`

@@snip [config](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/SerializationDocSpec.scala) { #serialization-bindings }

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
open ended types that might be target for [serialization gadgets](https://medium.com/@cowtowncoder/on-jackson-cves-dont-panic-here-is-what-you-need-to-know-54cd0d6e8062),
such as:

* `java.lang.Object`
* `java.io.Serializable`
* `java.util.Comparable`.

The blacklist of possible serialization gadget classes defined by Jackson databind are checked
and disallowed for deserialization.

### Formats

The following formats are supported, and you select which one to use in the `serialization-bindings`
configuration as described above.

* `jackson-json` - ordinary text based JSON
* `jackson-cbor` - binary [CBOR data format](https://github.com/FasterXML/jackson-dataformats-binary/tree/master/cbor)
* `jackson-smile` - binary [Smile data format](https://github.com/FasterXML/jackson-dataformats-binary/tree/master/smile)

The binary formats are more compact and have slightly better better performance than the JSON format.

TODO: It's undecided if we will support both CBOR or and Smile since the difference is small

## Annotations

TODO examples when annotations are needed

## Schema Evolution

When using Event Sourcing, but also for rolling updates, schema evolution becomes an important aspect of
developing your application. The requirements as well as our own understanding of the business domain may
(and will) change over time.

The Jackson serializer provides a way to perform transformations of the JSON tree model during deserialization.
This is working in the same way for the textual and binary formats.

We will look at a few scenarios of how the classes may be evolved.

### Remove Field

Removing a field can be done without any migration code. The Jackson serializer will ignore properties that does
not exist in the class.

### Add Field

Adding an optional field can be done without any migration code. The default value will be @scala[None]@java[`Optional.empty`].

Old class:

Java
:  @@snip [ItemAdded.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v1/ItemAdded.java) { #add-optional }

TODO: Scala examples

New class with a new optional `discount` property and a new `note` field with default value:

Java
:  @@snip [ItemAdded.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2a/ItemAdded.java) { #add-optional }

Let's say we want to have a mandatory `discount` property without default value instead:

Java
:  @@snip [ItemAdded.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2b/ItemAdded.java) { #add-mandatory }

To add a new mandatory field we have to use a `JacksonMigration` class and set the default value in the migration code.

This is how a migration class would look like for adding a `discount` field:

Java
:  @@snip [ItemAddedMigration.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2b/ItemAddedMigration.java) { #add-mandatory }

Override the `currentVersion` method to define the version number of the current (latest) version. The first version,
when no migration was used, is always 1. Increase this version number whenever you perform a change that is not
backwards compatible without migration code.

Implement the transformation of the old JSON structure to the new JSON structure in the `transform` method.
The [JsonNode](https://fasterxml.github.io/jackson-databind/javadoc/2.9/com/fasterxml/jackson/databind/JsonNode.html)
is mutable so you can add and remove fields, or change values. Note that you have to cast to specific sub-classes
such as [ObjectNode](https://fasterxml.github.io/jackson-databind/javadoc/2.9/com/fasterxml/jackson/databind/node/ObjectNode.html)
and [ArrayNode](https://fasterxml.github.io/jackson-databind/javadoc/2.9/com/fasterxml/jackson/databind/node/ArrayNode.html)
to get access to mutators.

The migration class must be defined in configuration file:

@@snip [config](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/SerializationDocSpec.scala) { #migrations-conf }

### Rename Field

Let's say that we want to rename the `productId` field to `itemId` in the previous example.

Java
:  @@snip [ItemAdded.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2c/ItemAdded.java) { #rename }

The migration code would look like:

Java
:  @@snip [ItemAddedMigration.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2c/ItemAddedMigration.java) { #rename }

### Structural Changes

In a similar way we can do arbitrary structural changes.

Old class:

Java
:  @@snip [Customer.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v1/Customer.java) { #structural }

New class:

Java
:  @@snip [Customer.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2a/Customer.java) { #structural }

with the `Address` class:

Java
:  @@snip [Address.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2a/Address.java) { #structural }

The migration code would look like:

Java
:  @@snip [CustomerMigration.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2a/CustomerMigration.java) { #structural }

### Rename Class

It is also possible to rename the class. For example, let's rename `OrderAdded` to `OrderPlaced`.

Old class:

Java
:  @@snip [OrderAdded.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v1/OrderAdded.java) { #rename-class }

New class:

Java
:  @@snip [OrderPlaced.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2a/OrderPlaced.java) { #rename-class }

The migration code would look like:

Java
:  @@snip [OrderPlacedMigration.java](/akka-serialization-jackson/src/test/java/jdoc/akka/serialization/jackson/v2a/OrderPlacedMigration.java) { #rename-class }

Note the override of the `transformClassName` method to define the new class name.

That type of migration must be configured with the old class name as key. The actual class can be removed.

@@snip [config](/akka-serialization-jackson/src/test/scala/doc/akka/serialization/jackson/SerializationDocSpec.scala) { #migrations-conf-rename }

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

TODO: The binary formats are currently also compressed. That may change since it might not be needed for those.
