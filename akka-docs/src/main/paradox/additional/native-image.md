---
project.description: How to build native image executables with Akka
---
# Building Native Images

Building native images with Akka is supported, both for local and cluster actor system applications. 

Most built-in features in Akka can be used as is, however some functionality is not available and some extension points
require additional metadata for reflective access to work.

## Unsupported features

The following features cannot be used in native image apps:

* Lightbend Telemetry
* Aeron UDP remoting
* Testkits
* LevelDB and InMem Akka Persistence plugins
* @ref[Durable storage](../typed/distributed-data.md#durable-storage) for Akka Distributed Data

## Features requiring additional metadata

In general, any feature that allows a custom implementation provided by a third party library or user code that is
then plugged into Akka through an entry in the application config file needs to have reflection metadata explicitly added
as described in the [GraalVM docs here](https://www.graalvm.org/latest/reference-manual/native-image/metadata/).

A few examples such classes are:

 * Custom Serializers
 * Custom Akka Extensions
 * Custom Mailbox Types
 * Akka Persistence plugins
 * Akka Discovery implementations
 * Akka Lease implementations

For plugins provided in libraries, metadata is preferably provided with the library so that end user applications
does not need to provide metadata for them.

### Serialization with Jackson

When using the built-in @apidoc[JsonSerializable] and @apidoc[CborSerializable] marker traits, message types are automatically added
for reflective access by Akka. 

If instead using marker self-defined traits, the marker trait defined in `serialization-bindings` as well as each 
concrete message types (lookup and constructor) needs to be added to the reflection metadata.

Applications that define and configure @apidoc[JacksonMigration] to evolve data formats needs to list each concrete
migration implementation (lookup and constructor) in reflection metadata.

Additional object mappers added through config `akka.serialization.jackson.jackson-modules` needs entries in the reflective 
metadata (lookup and constructor).

### Third party serializers

Third party serializers will need reflection metadata for the serializer implementation, the types used in `serialization-bindings`
and possibly further per-message metadata depending on the specific serializer logic.

### Extensions

Classic and typed `Extension`s loaded via configuration (`akka.extensions`, `akka.actor.typed.extensions`, `akka.actor.library-extensions` or `akka.actor.typed.library-extensions`)
needs an entry in the reflection metadata (lookup and constructor).

### Akka Persistence Event Adapters

Event adapters defined in an application needs to be listed in reflection metadata (lookup and constructor).

### Reflective classic actor construction

Classic actors that has a @apidoc[akka.actor.Props$] defined using the @scala[type `Props[T]()` or class `Props(classOf[T], ...)`] 
@java[class `Props.create(T.getClass, ...)`] needs reflection entries for lookup and the constructor matching the set of passed parameters. 

An easier path is to instead use lambda factories @scala[type `Props(new T)`]
@java[class `Props.create(T.getClass, () -> new T())`] to define props. The only reason to use the reflection based approach
is the classic remote deploy feature.
