# Flow.lazyFlow

Defers creation and materialization of a `Flow` until there is a first element.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #lazyFlow }

@@@

## Description

When the first element comes from upstream the actual `Flow` is created and materialized.
The internal `Flow` will not be created if there are no elements on completion or failure of up or downstream.

The materialized value of the `Flow` will be the materialized value of the created internal flow if it is materialized
and failed with a `akka.stream.NeverMaterializedException` if the stream fails or completes without the flow being materialized.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the internal flow is successfully created and it emits

**backpressures** when the internal flow is successfully created and it backpressures

**completes** when upstream completes and all elements have been emitted from the internal flow

**completes** when upstream completes and all futures have been completed and all elements have been emitted

@@@

