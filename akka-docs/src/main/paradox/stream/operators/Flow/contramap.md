# contramap

Transform this Flow by applying a function to each *incoming* upstream element before it is passed to the Flow.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.contramap](Flow) { scala="#contramap[In2](f:In2=&gt;In):Flow[In2, Out, Mat]" java="#map(
akka.japi.function.Function)" }

## Description

Transform this Flow by applying a function to each *incoming* upstream element before it is passed to the Flow.

## Examples

Scala
:  @@snip [Flow.scala](/akka-docs/src/test/scala/docs/stream/operators/ContraMap.scala) { #imports #contramap }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the mapping function returns an element

**backpressures** '''Backpressures when''' original flow backpressures

**completes** when upstream completes

**cancels** when original flow cancels

@@@
