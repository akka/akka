# limit

Limit number of element from upstream to given `max` number.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.limit](Flow$) { scala="#limit(max:Long):FlowOps.this.Repr[Out]" java="#limit(long)" } 

## Description

Limits the number of elements from upstream to a given `max` number, if the limit is passed the operator fails the stream with a @apidoc[StreamLimitReachedException](StreamLimitReachedException).

See also @ref:[limitWeighted](limitWeighted.md) which can choose a weight for each element counting to a total max limit weight. @ref:[take](take.md) is also closely related but completes the stream instead of failing it after a certain number of elements.

## Example

`limit` can protect a stream coming from an untrusted source into an in-memory aggregate that grows with the number of elements from filling the heap and causing an out-of-memory error.
In this sample we take at most 10 000 of the untrusted source elements into the aggregated sequence of elements, if the untrusted source emits more elements the stream and the materialized @scala[`Future[Seq[String]]`]@java[`CompletionStage<List<String>>`] will be failed:

Scala
:   @@snip [Limit.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Limit.scala) { #simple }

Java
:   @@snip [Limit.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/Limit.java) { #simple }


## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits and the number of emitted elements has not reached max

**backpressures** when downstream backpressures

**completes** when upstream completes and the number of emitted elements has not reached max

@@@
