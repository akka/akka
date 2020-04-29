# foldAsync

Just like `fold` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.foldAsync](Source) { scala="#foldAsync[T](zero:T)(f:(T,Out)=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#foldAsync(java.lang.Object,akka.japi.function.Function2)" }
@apidoc[Flow.foldAsync](Flow) { scala="#foldAsync[T](zero:T)(f:(T,Out)=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#foldAsync(java.lang.Object,akka.japi.function.Function2)" }

## Description

Just like `fold` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

Note that the `zero` value must be immutable.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

**backpressures** when downstream backpressures

**completes** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

@@@

