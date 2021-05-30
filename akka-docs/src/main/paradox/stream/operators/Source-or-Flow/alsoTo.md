# alsoTo

Attaches the given `Sink` to this `Flow`, meaning that elements that pass through this `Flow` will also be sent to the `Sink`.

@ref[Fan-out operators](../index.md#fan-out-operators)

## Signature

@apidoc[Source.alsoTo](Source) { scala="#alsoTo(that:akka.stream.Graph[akka.stream.SinkShape[Out],_]):FlowOps.this.Repr[Out]" java="#alsoTo(akka.stream.Graph)" }
@apidoc[Flow.alsoTo](Flow) { scala="#alsoTo(that:akka.stream.Graph[akka.stream.SinkShape[Out],_]):FlowOps.this.Repr[Out]" java="#alsoTo(akka.stream.Graph)" }


## Description

Attaches the given `Sink` to this `Flow`, meaning that elements that pass through this `Flow` will also be sent to the `Sink`.

## Reactive Streams semantics

@@@div { .callout }

**emits** when an element is available and demand exists both from the `Sink` and the downstream

**backpressures** when downstream or `Sink` backpressures

**completes** when upstream completes

**cancels** when downstream or `Sink` cancels

@@@


