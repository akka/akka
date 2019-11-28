# actorRef

Materialize an `ActorRef`; sending messages to it will emit them on the stream.

@ref[Source operators](../index.md#source-operators)

@@@ div { .group-scala }
## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #actorRef }
@@@

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

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and there are messages in the buffer or a message is sent to the `ActorRef`

**completes** when the actor is stopped by sending it a particular message as described above

@@@

## Examples


Scala
:  @@snip [actorRef.scala](/akka-docs/src/test/scala/docs/stream/operators/SourceOperators.scala) { #actorRef }

Java
:  @@snip [actorRef.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceDocExamples.java) { #actor-ref-imports #actor-ref }
