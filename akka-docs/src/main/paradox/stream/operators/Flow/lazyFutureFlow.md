# Flow.lazyFutureFlow

Defers creation and materialization of a `Flow` until there is a first element.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.lazyFutureFlow](Flow$) { scala="#lazyFutureFlow[I,O,M](create:()=&gt;scala.concurrent.Future[akka.stream.scaladsl.Flow[I,O,M]]):akka.stream.scaladsl.Flow[I,O,scala.concurrent.Future[M]]" }


## Description

When the first element comes from upstream the actual `Future[Flow]` is created and when that completes it is materialized
and inserted in the stream.
The internal `Flow` will not be created if there are no elements on completion or failure of up or downstream.

The materialized value of the `Flow` will be the materialized value of the created internal flow if it is materialized
and failed with a `akka.stream.NeverMaterializedException` if the stream fails or completes without the flow being materialized.

See also @ref:[lazyFlow](lazyFlow.md).

Can be combined with `prefixAndTail(1)` to base the flow construction on the initial element triggering creation.
See @ref:[lazyFlow](lazyFlow.md) for sample.

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

