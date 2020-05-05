# Flow.futureFlow

Streams the elements through the given future flow once it successfully completes.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.futureFlow](Flow$) { scala="#futureFlow[I,O,M](flow:scala.concurrent.Future[akka.stream.scaladsl.Flow[I,O,M]]):akka.stream.scaladsl.Flow[I,O,scala.concurrent.Future[M]]" }


## Description

Streams the elements through the given future flow once it successfully completes. 
If the future fails the stream is failed.

## Examples

A deferred creation of the stream based on the initial element can be achieved by combining `futureFlow`
with `prefixAndTail` like so:

Scala
:   @@snip [FutureFlow.scala](/akka-docs/src/test/scala/docs/stream/operators/flow/FutureFlow.scala) { #base-on-first-element }



## Reactive Streams semantics

@@@div { .callout }

**emits** when the internal flow is successfully created and it emits

**backpressures** when the internal flow is successfully created and it backpressures

**completes** when upstream completes and all elements have been emitted from the internal flow

**completes** when upstream completes and all futures have been completed and all elements have been emitted

@@@

