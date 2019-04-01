# Flow.asFlowWithContext

Turns a Flow into a FlowWithContext which can propagate a context per element along a stream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #asFlowWithContext }

@@@

## Description

Turns a Flow into a FlowWithContext which can propagate a context per element along a stream.
The first function passed into asFlowWithContext must turn each incoming pair of element and context value into an element of this Flow.
The second function passed into asFlowWithContext must turn each outgoing element of this Flow into an outgoing context value. 
