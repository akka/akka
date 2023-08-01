# ActorSource.actorRef

Materialize an @java[`ActorRef<T>`]@scala[`ActorRef[T]`] of the new actors API; sending messages to it will emit them on the stream only if they are of the same type as the stream.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Dependency

The Akka dependencies are available from Akka's library repository. To access them there, you need to configure the URL for this repository.

@@repository [sbt,Maven,Gradle] {
id="akka-repository"
name="Akka library repository"
url="https://repo.akka.io/maven"
}

This operator is included in:

@@dependency[sbt,Maven,Gradle] {
  bomGroup=com.typesafe.akka bomArtifact=akka-bom_$scala.binary.version$ bomVersionSymbols=AkkaVersion
  symbol1=AkkaVersion
  value1="$akka.version$"
  group="com.typesafe.akka"
  artifact="akka-stream-typed_$scala.binary.version$"
  version=AkkaVersion
}

## Signature

@apidoc[ActorSource.actorRef](ActorSource$) { scala="#actorRef[T](completionMatcher:PartialFunction[T,Unit],failureMatcher:PartialFunction[T,Throwable],bufferSize:Int,overflowStrategy:akka.stream.OverflowStrategy):akka.stream.scaladsl.Source[T,akka.actor.typed.ActorRef[T]]" java="#actorRef(java.util.function.Predicate,akka.japi.function.Function,int,akka.stream.OverflowStrategy)" }

## Description

Materialize an @java[`ActorRef<T>`]@scala[`ActorRef[T]`] which only accepts messages that are of the same type as the stream.

See also:

* @ref[ActorSource.actorRefWithBackpressure](actorRefWithBackpressure.md) This operator, but with backpressure control
* @ref[Source.actorRef](../Source/actorRef.md) The corresponding operator for the classic actors API
* @ref[Source.actorRefWithBackpressure](../Source/actorRefWithBackpressure.md) The operator for the classic actors API with backpressure control
* @ref[Source.queue](../Source/queue.md) Materialize a `SourceQueue` onto which elements can be pushed for emitting from the source

## Examples

Scala
:  @@snip [ActorSourceSinkExample.scala](/akka-stream-typed/src/test/scala/docs/akka/stream/typed/ActorSourceSinkExample.scala) { #actor-source-ref }

Java
:  @@snip [ActorSourceExample.java](/akka-stream-typed/src/test/java/docs/akka/stream/typed/ActorSourceExample.java) { #actor-source-ref }
