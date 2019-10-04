# ActorSource.actorRef

Materialize an @java[`ActorRef<T>`]@scala[`ActorRef[T]`]; sending messages to it will emit them on the stream only if they are of the same type as the stream.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Dependency

This operator is included in:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-stream-typed_$scala.binary_version$"
  version="$akka.version$"
}

@@@div { .group-scala }

## Signature

@@signature [ActorSource.scala](/akka-stream-typed/src/main/scala/akka/stream/typed/scaladsl/ActorSource.scala) { #actorRef }

@@@

## Description

Materialize an @java[`ActorRef<T>`]@scala[`ActorRef[T]`] which only accepts messages that are of the same type as the stream.

## Examples

Scala
:  @@snip [ActorSourceSinkExample.scala](/akka-stream-typed/src/test/scala/docs/akka/stream/typed/ActorSourceSinkExample.scala) { #actor-source-ref }

Java
:  @@snip [ActorSourceExample.java](/akka-stream-typed/src/test/java/docs/akka/stream/typed/ActorSourceExample.java) { #actor-source-ref }
