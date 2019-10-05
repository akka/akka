# failed

Fail directly with a user specified exception.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #failed }

@@@

## Description

Fail directly with a user specified exception.

## Reactive Streams semantics

@@@div { .callout }

**emits** never

**completes** fails the stream directly with the given exception

@@@

