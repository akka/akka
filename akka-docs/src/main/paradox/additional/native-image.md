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

Examples of such classes are:

 * Custom Serializers
 * Custom Mailbox Types
 * Akka Persistence plugins
 * Akka Discovery implementations
 * Akka Lease implementations

For plugins provided in libraries, metadata is preferably provided with the library so that end user applications
do not need to provide metadata for them.

### Serialization with Jackson

When using the built-in @apidoc[JsonSerializable] and @apidoc[CborSerializable] marker traits, message types are automatically added
for reflective access by Akka. 

If marker self-defined traits are being used instead, then the marker trait defined in `serialization-bindings`, as well as each 
concrete message type (lookup and constructor), need to be added to the reflection metadata.

Applications that define and configure @apidoc[JacksonMigration] to evolve data formats need to list each concrete
migration implementation (lookup and constructor) in reflection metadata.

Additional object mappers added through config `akka.serialization.jackson.jackson-modules` need entries in the reflective 
metadata (lookup and constructor).

### Third party serializers

Third party serializers will need reflection metadata for the serializer implementation, the types used in `serialization-bindings`
and possibly further per-message metadata depending on the specific serializer logic.

### Extensions

Classic and typed `Extension`s loaded via configuration (`akka.extensions`, `akka.actor.typed.extensions`, `akka.actor.library-extensions` or `akka.actor.typed.library-extensions`)
need an entry in the reflection metadata (lookup and constructor).

### Akka Persistence Event Adapters

Event adapters defined in an application need to be listed in reflection metadata (lookup and constructor).

### Reflective classic actor construction

Classic actors that have a @apidoc[akka.actor.Props$] defined using the @scala[type `Props[T]()` or class `Props(classOf[T], ...)`] 
@java[class `Props.create(T.getClass, ...)`] need reflection entries for lookup and the constructor matching the set of passed parameters. 

An easier path is to instead use lambda factories @scala[type `Props(new T)`]
@java[class `Props.create(T.getClass, () -> new T())`] to define props. The only reason to use the reflection based approach
is the classic remote deploy feature.

### Logging

When using `akka-slf4j` for logging, automatically used for `akka-actor-typed`, it is very likely that the concrete logger
chosen needs extra configuration.

While Akka does not mandate a logger implementation, `logback-classic` is used in many Akka samples throughout the Akka projects. 
Becausue of this Akka provides reflection metadata for logback out of the box, however projects using it will need an extra
native image flag `--initialize-at-build-time=ch.qos.logback`.

FIXME maybe just provide the lazy appender out of the box as well? 
      alternatively should we provide a lazy async logger appender or show it here?

If using `ch.qos.logback.classic.AsyncAppender` you will need to declare your own lazy version of the appender (not starting any threads at native image build time).

FIXME should we add link to working sample/config for logback?
