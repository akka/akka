# Sink.actorRefWithBackpressure

Send the elements from the stream to an `ActorRef` which must then acknowledge reception after completing a message, to provide back pressure onto the sink.

@ref[Sink operators](../index.md#sink-operators)

## Description

Send the elements from the stream to an `ActorRef` which must then acknowledge reception after completing a message,
to provide back pressure onto the sink.

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

