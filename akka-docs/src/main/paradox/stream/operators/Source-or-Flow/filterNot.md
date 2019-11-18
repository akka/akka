# filterNot

Filter the incoming elements using a predicate.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #filterNot }

@@@

## Description

Filter the incoming elements using a predicate. If the predicate returns false the element is passed downstream, if
it returns true the element is discarded.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the given predicate returns false for the element

**backpressures** when the given predicate returns false for the element and downstream backpressures

**completes** when upstream completes

@@@

## API docs

@apidoc[Flow.filterNot](Flow) { scala="#filterNot(p:Out=%3EBoolean):FlowOps.this.Repr[Out]" java="#filterNot(akka.japi.function.Predicate)" }
