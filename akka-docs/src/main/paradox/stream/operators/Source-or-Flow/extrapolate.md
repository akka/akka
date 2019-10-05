# extrapolate

Allow for a faster downstream by expanding the last emitted element to an `Iterator`.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #extrapolate }

@@@

## Description

Allow for a faster downstream by expanding the last emitted element to an `Iterator`. For example, an
`Iterator.continually(element)` will cause `extrapolate` to keep repeating the last emitted element. 

All original elements are always emitted unchanged - the `Iterator` is only used whenever there is downstream
 demand before upstream emits a new element.

Includes an optional `initial` argument to prevent blocking the entire stream when there are multiple producers.

See @ref:[Understanding extrapolate and expand](../../stream-rate.md#understanding-extrapolate-and-expand) for more information
and examples.

## Reactive Streams semantics

@@@div { .callout }

**emits** when downstream stops backpressuring

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

