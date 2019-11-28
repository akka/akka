# combine

Combine several sinks into one using a user specified strategy

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #combine }

@@@

## Description

Combine several sinks into one using a user specified strategy

## Reactive Streams semantics

@@@div { .callout }

**cancels** depends on the strategy

**backpressures** depends on the strategy

@@@

