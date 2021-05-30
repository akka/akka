# Sink.foreachParallel

Like `foreach` but allows up to `parallellism` procedure calls to happen in parallel.

@ref[Sink operators](../index.md#sink-operators)

@@@warning { title="Deprecated" }

Use @ref[`foreachAsync`](foreachAsync.md) instead (this is deprecated since Akka 2.5.17).

@@@

## Reactive Streams semantics

@@@div { .callout }

**cancels** never

**backpressures** when the previous parallel procedure invocations has not yet completed

@@@

