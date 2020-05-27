# Sink.lazySink

Defers creation and materialization of a `Sink` until there is a first element.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.lazySink](Sink$) { scala="#lazySink[T,M](create:()=&gt;akka.stream.scaladsl.Sink[T,M]):akka.stream.scaladsl.Sink[T,scala.concurrent.Future[M]]" java="#lazySink(akka.japi.function.Creator)" }


## Description

Defers `Sink` creation and materialization until when the first element arrives from upstream to the `lazySink`.  After
that the stream behaves as if the nested sink replaced the `lazySink`.
The nested `Sink` will not be created if upstream completes or fails without any elements arriving at the sink.

The materialized value of the `Sink` is a @scala[`Future`]@java[`CompletionStage`] that is completed with the 
materialized value of the nested sink once that is constructed.

Can be combined with @ref[prefixAndTail](../Source-or-Flow/prefixAndTail.md) to base the sink on the first element.

See also: 

 * @ref:[Sink.lazyFutureSink](lazyFutureSink.md) and @ref:[lazyCompletionStageSink](lazyCompletionStageSink.md).
 * @ref:[Source.lazySource](../Source/lazySource.md)
 * @ref:[Flow.lazyFlow](../Flow/lazyFlow.md)

## Examples

In this example we side effect from `Flow.map`, the sink factory and `Sink.foreach` so that the order becomes visible,
the nested sink is only created once the element has passed `map`: 

Scala
:   @@snip [Lazy.scala](/akka-docs/src/test/scala/docs/stream/operators/sink/Lazy.scala) { #simple-example }

Java
:   @@snip [Lazy.java](/akka-docs/src/test/java/jdocs/stream/operators/sink/Lazy.java) { #simple-example }



## Reactive Streams semantics

@@@div { .callout }

**cancels** if the future fails or if the created sink cancels 

**backpressures** when initialized and when created sink backpressures

@@@


