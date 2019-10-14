# scanAsync

Just like `scan` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #scanAsync }

@@@

## Description

Just like `scan` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@@@ warning

Note that the `zero` value must be immutable, because otherwise
the same mutable instance would be shared across different threads
when running the stream more than once.

@@@

## Reactive Streams semantics

@@@div { .callout }

**emits** when the @scala[`Future`] @java[`CompletionStage`] resulting from the function scanning the element resolves to the next value

**backpressures** when downstream backpressures

**completes** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

@@@

## Examples

Below example demonstrates how `scanAsync` is similar to `fold`, but it keeps value from every iteration.

Scala
:  @@snip [ScanAsync.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/ScanAsync.scala) { #scanAsync }

Java
:  @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #scanAsync }

@@@ warning

In an actual application the future would probably involve some external API that returns a @scala[`Future`]
@java[`CompletionStage`] rather than an immediately completed value.

@@@