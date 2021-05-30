# divertTo

Each upstream element will either be diverted to the given sink, or the downstream consumer according to the predicate function applied to the element.

@ref[Fan-out operators](../index.md#fan-out-operators)

## Signature

@apidoc[Source.divertTo](Source) { scala="#divertTo(that:akka.stream.Graph[akka.stream.SinkShape[Out],_],when:Out=&gt;Boolean):FlowOps.this.Repr[Out]" java="#divertTo(akka.stream.Graph,akka.japi.function.Predicate)" }
@apidoc[Flow.divertTo](Flow) { scala="#divertTo(that:akka.stream.Graph[akka.stream.SinkShape[Out],_],when:Out=&gt;Boolean):FlowOps.this.Repr[Out]" java="#divertTo(akka.stream.Graph,akka.japi.function.Predicate)" }


## Description

Each upstream element will either be diverted to the given sink, or the downstream consumer according to the predicate function applied to the element.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the chosen output stops backpressuring and there is an input element available

**backpressures** when the chosen output backpressures

**completes** when upstream completes and no output is pending

**cancels** when any of the downstreams cancel

@@@

