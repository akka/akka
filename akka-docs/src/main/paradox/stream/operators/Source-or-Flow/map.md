# map

Transform each element in the stream by calling a mapping function with it and passing the returned value downstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.map](Source) { scala="#map[T](f:Out=&gt;T):FlowOps.this.Repr[T]" java="#map(akka.japi.function.Function)" }
@apidoc[Flow.map](Flow) { scala="#map[T](f:Out=&gt;T):FlowOps.this.Repr[T]" java="#map(akka.japi.function.Function)" }

## Description

Transform each element in the stream by calling a mapping function with it and passing the returned value downstream.

## Examples

Scala
:  @@snip [Flow.scala](/akka-docs/src/test/scala/docs/stream/operators/Map.scala) { #imports #map }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the mapping function returns an element

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@
