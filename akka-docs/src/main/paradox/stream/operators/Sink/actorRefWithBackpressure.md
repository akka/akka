# Sink.actorRefWithBackpressure

Send the elements from the stream to an `ActorRef` (of the classic actors API) which must then acknowledge reception after completing a message, to provide back pressure onto the sink.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Signature

@apidoc[Sink.actorRefWithBackpressure](Sink$) { scala="#actorRefWithBackpressure[T](ref:akka.actor.ActorRef,onInitMessage:Any,ackMessage:Any,onCompleteMessage:Any,onFailureMessage:Throwable=&gt;Any):akka.stream.scaladsl.Sink[T,akka.NotUsed]" java="#actorRefWithBackpressure(akka.actor.ActorRef,java.lang.Object,java.lang.Object,java.lang.Object,akka.japi.function.Function)" }

## Description

Send the elements from the stream to an `ActorRef` which must then acknowledge reception after completing a message,
to provide back pressure onto the sink.
There is also a variant without a concrete acknowledge message accepting any message as such.

See also:

* @ref[`Sink.actorRef`](../Sink/actorRef.md) Send elements to an actor, without considering backpressure
* @ref[`ActorSink.actorRef`](../ActorSink/actorRef.md) The corresponding operator for the new actors API
* @ref[`ActorSink.actorRefWithBackpressure`](../ActorSink/actorRefWithBackpressure.md) Send elements to an actor of the new actors API supporting backpressure


## Example

Actor to be interacted with: 

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #actorRefWithBackpressure-actor }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #actorRefWithBackpressure-actor }

Using the `actorRefWithBackpressure` operator with the above actor:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #actorRefWithBackpressure }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #actorRefWithBackpressure }

## Reactive Streams semantics

@@@div { .callout }

**cancels** when the actor terminates

**backpressures** when the actor acknowledgement has not arrived

@@@

