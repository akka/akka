# expand

Like `extrapolate`, but does not have the `initial` argument, and the `Iterator` is also used in lieu of the original element, allowing for it to be rewritten and/or filtered.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #expand }

@@@

## Description

Like `extrapolate`, but does not have the `initial` argument, and the `Iterator` is also used in lieu of the original 
element, allowing for it to be rewritten and/or filtered.

See @ref:[Understanding extrapolate and expand](../../stream-rate.md#understanding-extrapolate-and-expand) for more information
and examples.

## Reactive Streams semantics

@@@div { .callout }

**emits** when downstream stops backpressuring

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

