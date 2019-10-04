# keepAlive

Injects additional (configured) elements if upstream does not emit for a configured amount of time.

@ref[Time aware operators](../index.md#time-aware-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #keepAlive }

@@@

## Description

Injects additional (configured) elements if upstream does not emit for a configured amount of time.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element or if the upstream was idle for the configured period

**backpressures** when downstream backpressures

**completes** when upstream completes

**cancels** when downstream cancels

@@@

