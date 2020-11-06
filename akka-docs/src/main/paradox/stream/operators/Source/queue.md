# Source.queue

Materialize a `BoundedSourceQueue` or `SourceQueue` onto which elements can be pushed for emitting from the source.

@ref[Source operators](../index.md#source-operators)

## Signature (`BoundedSourceQueue`)

@apidoc[Source.queue](Source$) { scala="#queue[T](bufferSize:Int):akka.stream.scaladsl.Source[T,akka.stream.scaladsl.BoundedSourceQueue[T]]" java="#queue(int)" }

## Description (`BoundedSourceQueue`)

The `BoundedSourceQueue` is an optimized variant of the `SourceQueue` with `OverflowStrategy.dropNew`. 
The `BoundedSourceQueue` will give immediate, synchronous feedback whether an element was accepted or not and is therefore recommended for situations where overload and dropping elements is expected and needs to be handled quickly.

In contrast, the `SourceQueue` offers more variety of `OverflowStrategies` but feedback is only asynchronously provided through a @scala[`Future`]@java[`CompletionStage`] value. 
In cases where elements need to be discarded quickly at times of overload to avoid out-of-memory situations, delivering feedback asynchronously can itself become a problem. 
This happens if elements come in faster than the feedback can be delivered in which case the feedback mechanism itself is part of the reason that an out-of-memory situation arises.

In summary, prefer `BoundedSourceQueue` over `SourceQueue` with `OverflowStrategy.dropNew` especially in high-load scenarios. 
Use `SourceQueue` if you need one of the other `OverflowStrategies`.

The `BoundedSourceQueue` contains a buffer that can be used by many producers on different threads.
When the buffer is full, the `BoundedSourceQueue` will not accept more elements.
The return value of `BoundedSourceQueue.offer()` immediately returns a `QueueOfferResult` (as opposed to an asynchronous value returned by `SourceQueue`).
A synchronous result is important in order to avoid situations where offer acknowledgements are handled slower than the rate of which elements are offered, which will eventually lead to an Out Of Memory error.

## Example (`BoundedSourceQueue`)

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #source-queue-synchronous }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #source-queue-synchronous }

## Signature (`SourceQueue`)

@apidoc[Source.queue](Source$) { scala="#queue[T](bufferSize:Int,overflowStrategy:akka.stream.OverflowStrategy):akka.stream.scaladsl.Source[T,akka.stream.scaladsl.SourceQueueWithComplete[T]]" java="#queue(int,akka.stream.OverflowStrategy)" }
@apidoc[Source.queue](Source$) { scala="#queue[T](bufferSize:Int,overflowStrategy:akka.stream.OverflowStrategy,maxConcurrentOffers:Int):akka.stream.scaladsl.Source[T,akka.stream.scaladsl.SourceQueueWithComplete[T]]" java="#queue(int,akka.stream.OverflowStrategy,int)" }

## Description (`SourceQueue`)

Materialize a `SourceQueue` onto which elements can be pushed for emitting from the source. The queue contains
a buffer, if elements are pushed onto the queue faster than the source is consumed the overflow will be handled with
a strategy specified by the user. Functionality for tracking when an element has been emitted is available through
`SourceQueue.offer`.

Using `Source.queue` you can push elements to the queue and they will be emitted to the stream if there is
demand from downstream, otherwise they will be buffered until request for demand is received. Elements in the buffer
will be discarded if downstream is terminated.

In combination with the queue, the @ref[`throttle`](./../Source-or-Flow/throttle.md) operator can be used to control the processing to a given limit, e.g. `5 elements` per `3 seconds`.

## Example (`SourceQueue`)

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #source-queue }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #source-queue }

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and the queue contains elements

**completes** when downstream completes

@@@

