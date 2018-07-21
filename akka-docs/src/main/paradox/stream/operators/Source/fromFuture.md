# fromFuture

Send the single value of the `Future` when it completes and there is demand.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #fromFuture }

@@@

## Description

Send the single value of the `Future` when it completes and there is demand.
If the future fails the stream is failed with that exception.


@@@div { .callout }

**emits** the future completes

**completes** after the future has completed

@@@

## Example
Scala
:  @@snip [SourceFromFuture.scala]($akka$/akka-docs/src/test/scala/docs/stream/operators/SourceOperators.scala) { #sourceFromFuture }

