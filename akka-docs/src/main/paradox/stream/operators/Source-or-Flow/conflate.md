# conflate

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there is backpressure.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #conflate }

@@@

## Description

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as
there is backpressure. The summary value must be of the same type as the incoming elements, for example the sum or
average of incoming numbers, if aggregation should lead to a different type `conflateWithSeed` can be used:

## Example

Scala
:   @@snip [SourceOrFlow.scala](/akka-docs/src/test/scala/docs/stream/operators/SourceOrFlow.scala) { #conflate }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #conflate }

If downstream is slower, the elements would be conflated by summing them
such that the upstream can continue producing elements, while waiting for downstream demand
e.g. no demand for some time, and later demand would result in `111` being emitted later on.

## Reactive Streams semantics 

@@@div { .callout }

**emits** when downstream stops backpressuring and there is a conflated element available

**backpressures** when the aggregate function cannot keep up with incoming elements

**completes** when upstream completes

@@@

