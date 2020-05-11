# Source.lazySource

Defers creation and materialization of a `Source` until there is demand.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.lazySource](Source$) { scala="#lazySource[T,M](create:()=&gt;akka.stream.scaladsl.Source[T,M]):akka.stream.scaladsl.Source[T,scala.concurrent.Future[M]]" java="#lazySource(akka.japi.function.Creator)" }


## Description

Defers creation and materialization of a `Source` until there is demand, then emits the elements from the source
downstream just like if it had been created up front.

See also @ref:[lazyFutureSource](lazyFutureSource.md).

Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
the laziness and will trigger the factory immediately.


## Reactive Streams semantics

@@@div { .callout }

**emits** depends on the wrapped `Source`

**completes** depends on the wrapped `Source`

@@@
