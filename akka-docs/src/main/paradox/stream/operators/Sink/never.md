# Sink.never

Always backpressure never cancel and never consume any elements from the stream.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.never](Sink$) { java="#never()" }
@apidoc[Sink.never](Sink$) { scala="#never()" }


## Description

A `Sink` that will always backpressure never cancel and never consume any elements from the stream.

## Reactive Streams semantics

@@@div { .callout }

**cancels** never

**backpressures** always

@@@


