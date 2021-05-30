# Flow.lazyInitAsync

Deprecated by @ref:[`Flow.lazyFutureFlow`](lazyFutureFlow.md) in combination with @ref:[`prefixAndTail`](../Source-or-Flow/prefixAndTail.md).

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.lazyInitAsync](Flow$) { scala="#lazyInitAsync[I,O,M](flowFactory:()=&gt;scala.concurrent.Future[akka.stream.scaladsl.Flow[I,O,M]]):akka.stream.scaladsl.Flow[I,O,scala.concurrent.Future[Option[M]]]" java="#lazyInitAsync(akka.japi.function.Creator)" }

## Description

`fromCompletionStage` has been deprecated in 2.6.0 use @ref:[lazyFutureFlow](lazyFutureFlow.md) in combination with @ref:[`prefixAndTail`](../Source-or-Flow/prefixAndTail.md)) instead.

Defers creation until a first element arrives.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the internal flow is successfully created and it emits

**backpressures** when the internal flow is successfully created and it backpressures

**completes** when upstream completes and all elements have been emitted from the internal flow

**completes** when upstream completes and all futures have been completed and all elements have been emitted

**cancels** when downstream cancels (keep reading)
    The operator's default behaviour in case of downstream cancellation before nested flow materialization (future completion) is to cancel immediately.
     This behaviour can be controlled by setting the [[akka.stream.Attributes.NestedMaterializationCancellationPolicy.PropagateToNested]] attribute,
    this will delay downstream cancellation until nested flow's materialization which is then immediately cancelled (with the original cancellation cause).
@@@

