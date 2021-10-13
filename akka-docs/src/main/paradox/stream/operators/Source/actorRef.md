# Source.actorRef

Materialize an `ActorRef` of the classic actors API; sending messages to it will emit them on the stream.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Signature

@apidoc[Source.actorRef](Source$) { scala="#actorRef[T](completionMatcher:PartialFunction[Any,akka.stream.CompletionStrategy],failureMatcher:PartialFunction[Any,Throwable],bufferSize:Int,overflowStrategy:akka.stream.OverflowStrategy):akka.stream.scaladsl.Source[T,akka.actor.ActorRef]" java="#actorRef(akka.japi.function.Function,akka.japi.function.Function,int,akka.stream.OverflowStrategy)" }

## Description

Materialize an `ActorRef`, sending messages to it will emit them on the stream. The actor contains
a buffer but since communication is one way, there is no back pressure. Handling overflow is done by either dropping
elements or failing the stream; the strategy is chosen by the user.

The stream can be completed successfully by sending the actor reference a `akka.actor.Status.Success`.
If the content is `akka.stream.CompletionStrategy.immediately` the completion will be signaled immediately.
Otherwise, if the content is `akka.stream.CompletionStrategy.draining` (or anything else)
already buffered elements will be sent out before signaling completion.
Sending `akka.actor.PoisonPill` will signal completion immediately but this behavior is deprecated and scheduled to be removed.
Using `akka.actor.ActorSystem.stop` to stop the actor and complete the stream is *not supported*.

See also:

* @ref[Source.actorRefWithBackpressure](../Source/actorRefWithBackpressure.md) This operator, but with backpressure control
* @ref[ActorSource.actorRef](../ActorSource/actorRef.md) The corresponding operator for the new actors API
* @ref[ActorSource.actorRefWithBackpressure](../ActorSource/actorRefWithBackpressure.md) The operator for the new actors API with backpressure control
* @ref[Source.queue](../Source/queue.md) Materialize a `SourceQueue` onto which elements can be pushed for emitting from the source

## Examples

Scala
:  @@snip [actorRef.scala](/akka-docs/src/test/scala/docs/stream/operators/SourceOperators.scala) { #actorRef }

Java
:  @@snip [actorRef.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceDocExamples.java) { #actor-ref-imports #actor-ref }

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and there are messages in the buffer or a message is sent to the `ActorRef`

**completes** when the actor is stopped by sending it a particular message as described above

@@@
