# Source.tick

A periodical repetition of an arbitrary object.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #tick }

@@@

## Description

A periodical repetition of an arbitrary object. Delay of first tick is specified
separately from interval of the following ticks.

## Reactive Streams semantics

@@@div { .callout }

**emits** periodically, if there is downstream backpressure ticks are skipped

**completes** never

@@@

