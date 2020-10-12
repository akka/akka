# Sink.takeLast

Collect the last `n` values emitted from the stream into a collection.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.takeLast](Sink$) { scala="#takeLast[T](n:Int):akka.stream.scaladsl.Sink[T,scala.concurrent.Future[scala.collection.immutable.Seq[T]]]" java="#takeLast(int)" }


## Description

Materializes into a @scala[`Future`] @java[`CompletionStage`] of @scala[`immutable.Seq[T]`] @java[`List<In>`] containing the last `n` collected elements when the stream completes.
If the stream completes before signaling at least n elements, the @scala[`Future`] @java[`CompletionStage`]  will complete with the number
of elements taken at that point. 
If the stream never completes, the @scala[`Future`] @java[`CompletionStage`] will never complete.
If there is a failure signaled in the stream the @scala[`Future`] @java[`CompletionStage`] will be completed with failure.

## Example

Scala
:   @@snip [TakeLastSinkSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/TakeLastSinkSpec.scala) { #takeLast-operator-example }

Java
:   @@snip [SinkDocExamples.java](/akka-docs/src/test/java/jdocs/stream/operators/SinkDocExamples.java) { #takeLast-operator-example }

## Reactive Streams semantics

@@@div { .callout }

**cancels** never

**backpressures** never

@@@
