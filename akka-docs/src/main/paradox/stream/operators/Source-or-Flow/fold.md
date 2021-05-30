# fold

Start with current value `zero` and then apply the current and next value to the given function. When upstream completes, the current value is emitted downstream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #fold }

@@@

## Description

Start with current value `zero` and then apply the current and next value to the given function. When upstream
completes, the current value is emitted downstream.

Note that the `zero` value must be immutable.


@@@div { .callout }

**emits** when upstream completes

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

