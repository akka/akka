# take

Pass `n` incoming elements downstream and then complete

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #take }

@@@

## Description

Pass `n` incoming elements downstream and then complete

## Reactive Streams semantics

@@@div { .callout }

**emits** while the specified number of elements to take has not yet been reached

**backpressures** when downstream backpressures

**completes** when the defined number of elements has been taken or upstream completes

@@@

