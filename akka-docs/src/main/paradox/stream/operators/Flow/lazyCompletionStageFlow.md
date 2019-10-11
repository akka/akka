# Flow.lazyCompletionStageFlow

Defers creation and materialization of a `Flow` until there is a first element.

@ref[Simple operators](../index.md#simple-operators)


## Description

When the first element comes from upstream the actual `CompletionStage<Flow>` is created and when that completes it is materialized
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

@@@

