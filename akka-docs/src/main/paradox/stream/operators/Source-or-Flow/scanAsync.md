# scanAsync

Just like @ref[`scan`](./scan.md) but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.scanAsync](Source) { scala="#scanAsync[T](zero:T)(f:(T,Out)=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#scanAsync(java.lang.Object,akka.japi.function.Function2)" }
@apidoc[Flow.scanAsync](Flow) { scala="#scanAsync[T](zero:T)(f:(T,Out)=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#scanAsync(java.lang.Object,akka.japi.function.Function2)" }


## Description

Just like `scan` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@@@ warning

Note that the `zero` value must be immutable, because otherwise
the same mutable instance would be shared across different threads
when running the stream more than once.

@@@

## Example

Below example demonstrates how `scanAsync` is similar to `fold`, but it keeps value from every iteration.

Scala
:  @@snip [ScanAsync.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/ScanAsync.scala) { #scan-async }

Java
:  @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #scan-async }

@@@ warning

In an actual application the future would probably involve some external API that returns a @scala[`Future`]
@java[`CompletionStage`] rather than an immediately completed value.

@@@

## Reactive Streams semantics

@@@div { .callout }

**emits** when the @scala[`Future`] @java[`CompletionStage`] resulting from the function scanning the element resolves to the next value

**backpressures** when downstream backpressures

**completes** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

@@@
