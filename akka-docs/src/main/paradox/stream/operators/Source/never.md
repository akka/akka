# never

Never emit any elements, never complete and never fail.

@ref[Source operators](../index.md#source-operators)

@ref:[`Source.empty`](empty.md), a source which emits nothing and completes immediately.

## Signature

@apidoc[Source.never](Source$) { scala="#never[T]:akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#never()" }

## Description

Create a source which never emits any elements, never completes and never failes. Useful for tests.

## Reactive Streams semantics

@@@div { .callout }

**emits** never

**completes** never

@@@
