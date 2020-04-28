# Source.empty

Complete right away without ever emitting any elements.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.empty](Source$) { scala="#empty[T]:akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#empty()" java="#empty(java.lang.Class)" }


## Description

Complete right away without ever emitting any elements. Useful when you have to provide a source to
an API but there are no elements to emit.

## Reactive Streams semantics

@@@div { .callout }

**emits** never

**completes** directly

@@@

