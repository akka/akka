# filterNot

Filter the incoming elements using a predicate.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.filterNot](Source) { scala="#filterNot(p:Out=&gt;Boolean):FlowOps.this.Repr[Out]" java="#filterNot(akka.japi.function.Predicate)" }
@apidoc[Flow.filterNot](Flow) { scala="#filterNot(p:Out=&gt;Boolean):FlowOps.this.Repr[Out]" java="#filterNot(akka.japi.function.Predicate)" }


## Description

Filter the incoming elements using a predicate. If the predicate returns false the element is passed downstream, if
it returns true the element is discarded.

See also @ref:[`filter`](filter.md).

## Example

For example, given a `Source` of words we can omit the shorter words with the `filterNot` operator: 

Scala
:  @@snip [Filter.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Filter.scala) { #filterNot }

Java
:  @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #filterNot }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the given predicate returns false for the element

**backpressures** when the given predicate returns false for the element and downstream backpressures

**completes** when upstream completes

@@@

## API docs

@apidoc[Flow.filterNot](Flow) { scala="#filterNot(p:Out=%3EBoolean):FlowOps.this.Repr[Out]" java="#filterNot(akka.japi.function.Predicate)" }
