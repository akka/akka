# Source.lazily

Deprecated by @ref:[`Source.lazySource`](lazySource.md).

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.lazily](Source$) { scala="#lazily[T,M](create:()=&gt;akka.stream.scaladsl.Source[T,M]):akka.stream.scaladsl.Source[T,scala.concurrent.Future[M]]" java="#lazily(akka.japi.function.Creator)" }


## Description

`lazily` has been deprecated in 2.6.0, use @ref:[lazySource](lazySource.md) instead.

Defers creation and materialization of a `Source` until there is demand.

## Reactive Streams semantics

@@@div { .callout }

**emits** depends on the wrapped `Source`

**completes** depends on the wrapped `Source`

@@@

