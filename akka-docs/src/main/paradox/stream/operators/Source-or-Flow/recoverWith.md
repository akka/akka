# recoverWith

Allow switching to alternative Source when a failure has happened upstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.recoverWith](Source) { scala="#recoverWith[T&gt;:Out](pf:PartialFunction[Throwable,akka.stream.Graph[akka.stream.SourceShape[T],akka.NotUsed]]):FlowOps.this.Repr[T]" java="#recoverWith(java.lang.Class,java.util.function.Supplier)" }
@apidoc[Flow.recoverWith](Flow) { scala="#recoverWith[T&gt;:Out](pf:PartialFunction[Throwable,akka.stream.Graph[akka.stream.SourceShape[T],akka.NotUsed]]):FlowOps.this.Repr[T]" java="#recoverWith(java.lang.Class,java.util.function.Supplier)" }


## Description

Allow switching to alternative Source when a failure has happened upstream.

Throwing an exception inside `recoverWith` _will_ be logged on ERROR level automatically.

## Reactive Streams semantics

@@@div { .callout }

**emits** the element is available from the upstream or upstream is failed and pf returns alternative Source

**backpressures** downstream backpressures, after failure happened it backprssures to alternative Source

**completes** upstream completes or upstream failed with exception pf can handle

@@@

