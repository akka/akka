# collect

Apply a partial function to each incoming element, if the partial function is defined for a value the returned value is passed downstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.collect](Source) { scala="#collect[T](pf:PartialFunction[Out,T]):FlowOps.this.Repr[T]" java="#collect(scala.PartialFunction)" }
@apidoc[Flow.collect](Flow) { scala="#collect[T](pf:PartialFunction[Out,T]):FlowOps.this.Repr[T]" java="#collect(scala.PartialFunction)" }

## Description

Apply a partial function to each incoming element, if the partial function is defined for a value the returned
value is passed downstream. This can often replace `filter` followed by `map` to achieve the same in one single operator.

@java[`collect` is supposed to be used with @apidoc[akka.japi.pf.PFBuilder] to construct the partial function.
There is also a @ref:[collectType](collectType.md) that often can be easier to use than the `PFBuilder` and
then combine with ordinary `filter` and `map` operators.]

## Example

Given stream element classes `Message`, `Ping`, and `Pong`, where `Ping` extends `Message` and `Pong` is an
unrelated class.

Scala
:   @@snip [Collect.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Collect.scala) { #collect-elements }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #collect-elements }


From a stream of `Message` elements we would like to collect all elements of type `Ping` that have an `id != 0`,
and then covert to `Pong` with same id.

Scala
:   @@snip [Collect.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Collect.scala) { #collect }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #collect }

@@@div { .group-java }
An alternative is to use `collectType`. The same conversion be written as follows, and it is as efficient.

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #collectType }

@@@

## Reactive Streams semantics

@@@div { .callout }

**emits** when the provided partial function is defined for the element

**backpressures** the partial function is defined for the element and downstream backpressures

**completes** when upstream completes

@@@
