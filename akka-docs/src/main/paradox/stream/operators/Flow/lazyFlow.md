# Flow.lazyFlow

Defers creation and materialization of a `Flow` until there is a first element.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.lazyFlow](Flow$) { scala="#lazyFlow[I,O,M](create:()=&gt;akka.stream.scaladsl.Flow[I,O,M]):akka.stream.scaladsl.Flow[I,O,scala.concurrent.Future[M]]" java="#lazyFlow(akka.japi.function.Creator)" }


## Description

Defers `Flow` creation and materialization until when the first element arrives at the `lazyFlow` from upstream. After
that the stream behaves as if the nested flow replaced the `lazyFlow`.
The nested `Flow` will not be created if the outer flow completes or fails before any elements arrive.

Note that asynchronous boundaries and many other operators in the stream may do pre-fetching or trigger demand and thereby making an early element come throught the stream leading to creation of the inner flow earlier than you would expect.

The materialized value of the `Flow` is a @scala[`Future`]@java[`CompletionStage`] that is completed with the 
materialized value of the nested flow once that is constructed.

See also: 

 * @ref:[flatMapPrefix](../Source-or-Flow/flatMapPrefix.md)
 * @ref:[Flow.lazyFutureFlow](lazyFutureFlow.md) and @ref:[Flow.lazyCompletionStageFlow](lazyCompletionStageFlow.md)
 * @ref:[Source.lazySource](../Source/lazySource.md)
 * @ref:[Sink.lazySink](../Sink/lazySink.md)

## Examples

In this sample we produce a short sequence of numbers, mostly to side effect and write to standard out to see in which
order things happen. Note how producing the first value in the `Source` happens before the creation of the flow:

Scala
:   @@snip [Lazy.scala](/akka-docs/src/test/scala/docs/stream/operators/flow/Lazy.scala) { #simple-example }

Java
:   @@snip [Lazy.java](/akka-docs/src/test/java/jdocs/stream/operators/flow/Lazy.java) { #simple-example }

Since the factory is called once per stream materialization it can be used to safely construct a mutable object to 
use with the actual deferred `Flow`. In this example we fold elements into an `ArrayList` created inside the lazy 
flow factory:

Scala
:   @@snip [Lazy.scala](/akka-docs/src/test/scala/docs/stream/operators/flow/Lazy.scala) { #mutable-example }

Java
:   @@snip [Lazy.java](/akka-docs/src/test/java/jdocs/stream/operators/flow/Lazy.java) { #mutable-example }

If we instead had used `fold` directly with an `ArrayList` we would have shared the same list across
all materialization and what is even worse, unsafely across threads.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the internal flow is successfully created and it emits

**backpressures** when the internal flow is successfully created and it backpressures

**completes** when upstream completes and all elements have been emitted from the internal flow

**completes** when upstream completes and all futures have been completed and all elements have been emitted

**cancels** when downstream cancels (keep reading)
    The operator's default behaviour in case of downstream cancellation before nested flow materialization (future completion) is to cancel immediately.
     This behaviour can be controlled by setting the [[akka.stream.Attributes.NestedMaterializationCancellationPolicy.PropagateToNested]] attribute,
    this will delay downstream cancellation until nested flow's materialization which is then immediately cancelled (with the original cancellation cause).
@@@
