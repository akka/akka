# Source.futureSource

Streams the elements of the given future source once it successfully completes.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.futureSource](Source$) { scala="#futureSource[T,M](futureSource:scala.concurrent.Future[akka.stream.scaladsl.Source[T,M]]):akka.stream.scaladsl.Source[T,scala.concurrent.Future[M]]" }


## Description

Streams the elements of the given future source once it successfully completes. 
If the future fails the stream is failed.

For the corresponding operator for the Java standard library `CompletionStage` see @ref:[completionStageSource](completionStageSource.md).

## Example

Suppose we are accessing a remote service that streams user data over HTTP/2 or a WebSocket. We can model that 
as a @apidoc[Source[User,NotUsed]] but that source will only be available once the connection has been established.

Scala
: @@snip [FutureSource.scala](/akka-docs/src/test/scala/docs/stream/operators/source/FutureSource.scala) { #sourceFutureSource }

## Reactive Streams semantics

@@@div { .callout }

**emits** the next value from the *future* source, once it has completed

**completes** after the *future* source completes

@@@
