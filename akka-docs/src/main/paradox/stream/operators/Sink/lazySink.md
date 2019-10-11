# Sink.lazySink

Defers creation and materialization of a `Sink` until there is a first element.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #lazySink }

@@@

## Description

When the first element comes from upstream the actual `Sink` is created and materialized.
The internal `Sink` will not be created if the stream completes of fails before any element got through.

The materialized value of the `Sink` will be the materialized value of the created internal flow if it is materialized
and failed with a `akka.stream.NeverMaterializedException` if the stream fails or completes without the flow being materialized.

Can be combined with @ref[prefixAndTail](../Source-or-Flow/prefixAndTail.md) to base the sink on the first element.

See also @ref:[lazyFutureSink](lazyFutureSink.md) and @ref:[lazyCompletionStageSink](lazyCompletionStageSink.md).

## Reactive Streams semantics

@@@div { .callout }

**cancels** if the future fails or if the created sink cancels 

**backpressures** when initialized and when created sink backpressures

@@@


