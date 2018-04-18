# queue

Materialize a `SourceQueue` onto which elements can be pushed for emitting from the source. 

@ref[Source stages](../index.md#source-stages)

@@@div { .group-scala }

## Signature

@@signature [Source.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #queue }

@@@

## Description

Materialize a `SourceQueue` onto which elements can be pushed for emitting from the source. The queue contains
a buffer, if elements are pushed onto the queue faster than the source is consumed the overflow will be handled with
a strategy specified by the user. Functionality for tracking when an element has been emitted is available through
`SourceQueue.offer`.


@@@div { .callout }

**emits** when there is demand and the queue contains elements

**completes** when downstream completes

@@@

