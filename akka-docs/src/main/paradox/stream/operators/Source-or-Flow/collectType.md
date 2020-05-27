# collectType 

Transform this stream by testing the type of each of the elements on which the element is an instance of the provided type as they pass through this processing step.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.collectType](Source) { scala="#collectType[T](implicittag:scala.reflect.ClassTag[T]):FlowOps.this.Repr[T]" java="#collectType(java.lang.Class)" }
@apidoc[Flow.collectType](Flow) { scala="#collectType[T](implicittag:scala.reflect.ClassTag[T]):FlowOps.this.Repr[T]" java="#collectType(java.lang.Class)" }


## Description

Filter elements that is of a given type.

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
:   @@snip [Collect.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Collect.scala) { #collectType }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #collectType }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the element is of the given type

**backpressures** the element is of the given type and downstream backpressures

**completes** when upstream completes

@@@
