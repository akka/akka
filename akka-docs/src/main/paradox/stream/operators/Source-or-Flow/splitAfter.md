# splitAfter

End the current substream whenever a predicate returns `true`, starting a new substream for the next element.

@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #splitAfter }

@@@

## Description

End the current substream whenever a predicate returns `true`, starting a new substream for the next element.

## Reactive Streams semantics

@@@div { .callout }

**emits** when an element passes through. When the provided predicate is true it emits the element * and opens a new substream for subsequent element

**backpressures** when there is an element pending for the next substream, but the previous is not fully consumed yet, or the substream backpressures

**completes** when upstream completes (Until the end of stream it is not possible to know whether new substreams will be needed or not)

@@@

