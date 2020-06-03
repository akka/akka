# Source.lazySource

Defers creation and materialization of a `Source` until there is demand.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.lazySource](Source$) { scala="#lazySource[T,M](create:()=&gt;akka.stream.scaladsl.Source[T,M]):akka.stream.scaladsl.Source[T,scala.concurrent.Future[M]]" java="#lazySource(akka.japi.function.Creator)" }


## Description

Defers creation and materialization of a `Source` until there is demand, then emits the elements from the source
downstream just like if it had been created up front. If the stream fails or cancels before there is demand the factory will not be invoked.

Note that asynchronous boundaries and many other operators in the stream may do pre-fetching or trigger demand earlier
than you would expect.

The materialized value of the `lazy` is a @scala[`Future`]@java[`CompletionStage`] that is completed with the 
materialized value of the nested source once that is constructed.

See also:
 
 * @ref:[Source.lazyFutureSource](lazyFutureSource.md) and @ref:[Source.lazyCompletionStageSource](lazyCompletionStageSource.md)
 * @ref:[Flow.lazyFlow](../Flow/lazyFlow.md)
 * @ref:[Sink.lazySink](../Sink/lazySink.md)

## Example

In this example you might expect this sample to not construct the expensive source until `.pull` is called. However, 
since `Sink.queue` has a buffer and will ask for that immediately on materialization the expensive source is in created
quickly after the stream has been materialized:

Scala
:   @@snip [Lazy.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Lazy.scala) { #not-a-good-example }

Java
:   @@snip [Lazy.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Lazy.java) { #not-a-good-example }

Instead the most useful aspect of the operator is that the factory is called once per stream materialization 
which means that it can be used to safely construct a mutable object to use with the actual deferred source. 

In this example we make use of that by unfolding a mutable object that works like an iterator with a method to say if 
there are more elements and one that produces the next and moves to the next element.

If the `IteratorLikeThing` was used directly in a `Source.unfold` the same instance would end up being unsafely shared
across all three materializations of the stream, but wrapping it with `Source.lazy` ensures we create a separate instance
for each of the started streams:

Scala
:   @@snip [Lazy.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Lazy.scala) { #one-per-materialization }

Java
:   @@snip [Lazy.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Lazy.java) { #one-per-materialization }

Note though that you can often also achieve the same using @ref:[unfoldResource](unfoldResource.md). If you have an actual `Iterator`
you should prefer @ref:[fromIterator](fromIterator.md).


## Reactive Streams semantics

@@@div { .callout }

**emits** depends on the wrapped `Source`

**completes** depends on the wrapped `Source`

@@@
