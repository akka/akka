# alsoToAll

Attaches the given @apidoc[Source]s to this @apidoc[Flow], meaning that elements that pass through this @apidoc[Flow] will also be sent to all those @apidoc[Sink]s.

@ref[Fan-out operators](../index.md#fan-out-operators)

## Signature

@apidoc[Source.alsoToAll](Source) { scala="#alsoToAll(that:akka.stream.Graph[akka.stream.SinkShape[Out],_]*):FlowOps.this.Repr[Out]" java="#alsoToAll(akka.stream.Graph*)" }
@apidoc[Flow.alsoToAll](Flow) { scala="#alsoToAll(that:akka.stream.Graph[akka.stream.SinkShape[Out],_]*):FlowOps.this.Repr[Out]" java="#alsoToAll(akka.stream.Graph*)" }

## Description

Attaches the given @apidoc[Source] s to this @apidoc[Flow], meaning that elements that pass through this @apidoc[Flow]
will also be sent to all those @apidoc[Sink]s.

## Reactive Streams semantics

@@@div { .callout }

**emits** when an element is available and demand exists both from the @apidoc[Sink]s and the downstream

**backpressures** when downstream or any of the @apidoc[Sink]s backpressures

**completes** when upstream completes

**cancels** when downstream or or any of the @apidoc[Sink]s cancels

@@@


