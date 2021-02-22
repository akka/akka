# foldAsync

Just like `fold` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.foldAsync](Source) { scala="#foldAsync[T](zero:T)(f:(T,Out)=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#foldAsync(java.lang.Object,akka.japi.function.Function2)" }
@apidoc[Flow.foldAsync](Flow) { scala="#foldAsync[T](zero:T)(f:(T,Out)=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#foldAsync(java.lang.Object,akka.japi.function.Function2)" }

## Description

Just like `fold` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@@@ warning

Note that the `zero` value must be immutable, because otherwise
the same mutable instance would be shared across different threads
when running the stream more than once.

@@@

## Example

`foldAsync` is typically used to 'fold up' the incoming values into an aggregate asynchronously. 
For example, you might want to summarize the incoming values into a histogram:

Scala
:   @@snip [FoldAsync.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/FoldAsync.scala) { #imports #foldAsync }

Java
:   @@snip [FoldAsync.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #foldAsync }

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

**backpressures** when downstream backpressures

**completes** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

@@@

