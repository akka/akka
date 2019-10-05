# divertTo

Each upstream element will either be diverted to the given sink, or the downstream consumer according to the predicate function applied to the element.

@ref[Fan-out operators](../index.md#fan-out-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #divertTo }

@@@

## Description

Each upstream element will either be diverted to the given sink, or the downstream consumer according to the predicate function applied to the element.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the chosen output stops backpressuring and there is an input element available

**backpressures** when the chosen output backpressures

**completes** when upstream completes and no output is pending

**cancels** when any of the downstreams cancel

@@@

