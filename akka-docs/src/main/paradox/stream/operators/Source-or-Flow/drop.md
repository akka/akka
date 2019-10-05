# drop

Drop `n` elements and then pass any subsequent element downstream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #drop }

@@@

## Description

Drop `n` elements and then pass any subsequent element downstream.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the specified number of elements has been dropped already

**backpressures** when the specified number of elements has been dropped and downstream backpressures

**completes** when upstream completes

@@@

