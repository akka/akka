# empty

Complete right away without ever emitting any elements.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #empty }

@@@

## Description

Complete right away without ever emitting any elements. Useful when you have to provide a source to
an API but there are no elements to emit.

## Reactive Streams semantics

@@@div { .callout }

**emits** never

**completes** directly

@@@

