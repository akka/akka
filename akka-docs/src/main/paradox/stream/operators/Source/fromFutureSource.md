# fromFutureSource

Streams the elements of the given future source once it successfully completes.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #fromFutureSource }

@@@

## Description

Streams the elements of the given future source once it successfully completes. 
If the future fails the stream is failed.


@@@div { .callout }

**emits** the next value from the *future* source, once it has completed

**completes** after the *future* source completes

@@@

