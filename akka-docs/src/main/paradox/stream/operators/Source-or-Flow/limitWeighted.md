# limitWeighted

Limit the total weight of incoming elements

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.limitWeighted](Flow) { scala="#limitWeighted[T](max:Long)(costFn:Out=&gt;Long):FlowOps.this.Repr[Out]" java="#limitWeighted(long,akka.japi.function.Function)" } 

## Description

A weight function returns the weight of each element, then the total accumulated weight  is compared to a max and if it has passed the max the stream is failed with a @apidoc[StreamLimitReachedException](StreamLimitReachedException).

See also @ref:[limit](limit.md) which puts a limit on the number of elements instead (the same as always returning `1` from the weight function).

## Examples

`limitWeighted` can protect a stream coming from an untrusted source into an in-memory aggregate that grows with the number of elements from filling the heap and causing an out-of-memory error.
In this sample we use the number of bytes in each `ByteString` element as weight and accept at most a total of 10 000 bytes from the untrusted source elements into the aggregated `ByteString` of all bytes, if the untrusted source emits more elements the stream and the materialized @scala[`Future[ByteString]`]@java[`CompletionStage<ByteString>`] will be failed:

Scala
:   @@snip [LimitWeighted.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/LimitWeighted.scala) { #simple }

Java
:   @@snip [LimitWeighted.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/LimitWeighted.java) { #simple }


## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits and the number of emitted elements has not reached max

**backpressures** when downstream backpressures

**completes** when upstream completes and the number of emitted elements has not reached max

@@@
