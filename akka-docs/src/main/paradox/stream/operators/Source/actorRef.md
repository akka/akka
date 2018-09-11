# actorRef

Materialize an `ActorRef`; sending messages to it will emit them on the stream.

@ref[Source operators](../index.md#source-operators)

@@@ div { .group-scala }
## Signature

@@signature [Source.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #actorRef }
@@@

## Description

Materialize an `ActorRef`, sending messages to it will emit them on the stream. The actor contains
a buffer but since communication is one way, there is no back pressure. Handling overflow is done by either dropping
elements or failing the stream; the strategy is chosen by the user.

@@@div { .callout }

**emits** when there is demand and there are messages in the buffer or a message is sent to the `ActorRef`

**completes** when the `ActorRef` is sent `akka.actor.Status.Success`

@@@

The created actor can be asked about the inner buffer state too. 

Scala
:   @@snip [SourceOperators.scala]($code$/scala/docs/stream/operators/SourceOperators.scala) { #sourceActorRefBufferSize }

Java
:   @@snip [ActorRefSource.java]($code$/java/jdocs/stream/operators/ActorRefSource.java) { #sourceActorRefBufferSize }

