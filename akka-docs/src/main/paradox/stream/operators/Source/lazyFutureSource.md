# Source.lazyFutureSource

Defers creation and materialization of a `Source` until there is demand.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.lazyFutureSource](Source$) { scala="#lazyFutureSource[T,M](create:()=&gt;scala.concurrent.Future[akka.stream.scaladsl.Source[T,M]]):akka.stream.scaladsl.Source[T,scala.concurrent.Future[M]]" }


## Description

Invokes the user supplied factory when the first downstream demand arrives. When the returned future completes 
successfully the source switches over to the new source and emits downstream just like if it had been created up front.

Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
the laziness and will trigger the factory immediately.

See also @ref:[lazySource](lazySource.md).

## Reactive Streams semantics

@@@div { .callout }

**emits** depends on the wrapped `Source`

**completes** depends on the wrapped `Source`

@@@

