# foreachAsync

Invoke a given procedure asynchronously for each element received.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #foreachAsync }

@@@

## Description

Invoke a given procedure asynchronously for each element received. Note that if shared state is mutated from the procedure that must be done in a thread-safe way.

The sink materializes into a  @scala[`Future[Done]`] @java[`CompletionStage<Done>`] which completes when the
stream completes, or fails if the stream fails.

## Example

Scala
:   @@snip [SinkRecipeDocSpec.scala](/akka-docs/src/test/scala/docs/stream/SinkRecipeDocSpec.scala) { #forseachAsync-processing }

Java
:   @@snip [SinkRecipeDocTest.java](/akka-docs/src/test/java/jdocs/stream/SinkRecipeDocTest.java) { #forseachAsync-processing }

## Reactive Streams semantics

@@@div { .callout }

**cancels** when a @scala[`Future`] @java[`CompletionStage`] fails

**backpressures** when the number of @scala[`Future`s] @java[`CompletionStage`s] reaches the configured parallelism

@@@


