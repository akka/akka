# Sink.unfoldResource

Wrap any resource that can be opened, written to, and closed using three distinct functions into a sink.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #unfoldResource }

@@@

## Description

Wrap any resource that can be opened, written to, and closed using three distinct functions into a sink.


@@@div { .callout }

**cancels** never

**backpressures** when writing to the resource is blocked

@@@

