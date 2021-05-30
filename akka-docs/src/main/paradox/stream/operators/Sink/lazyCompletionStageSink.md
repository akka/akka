# Sink.lazyCompletionStageSink

Defers creation and materialization of a `Sink` until there is a first element.

@ref[Sink operators](../index.md#sink-operators)


## Description

When the first element comes from upstream the `CompletionStage<Sink>` is created. When that completes successfully with a sink
that is materialized and inserted in the stream.
The internal `Sink` will not be created if the stream completes of fails before any element got through.

The materialized value of the `Sink` will be the materialized value of the created internal flow if it is materialized
and failed with a `akka.stream.NeverMaterializedException` if the stream fails or completes without the flow being materialized.

Can be combined with @ref:[prefixAndTail](../Source-or-Flow/prefixAndTail.md) to base the sink on the first element.

See also @ref:[lazySink](lazySink.md).

## Reactive Streams semantics

@@@div { .callout }

**cancels** if the future fails or if the created sink cancels 

**backpressures** when initialized and when created sink backpressures

@@@


