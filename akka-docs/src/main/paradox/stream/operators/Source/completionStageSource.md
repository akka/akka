# Source.completionStageSource

Streams the elements of an asynchronous source once its given *completion* operator completes.

@ref[Source operators](../index.md#source-operators)

@@@ div { .group-java }

## Signature

@apidoc[Source.completionStageSource](Source$) { java="#completionStageSource(java.util.concurrent.CompletionStage)" }

@@@

## Description

Streams the elements of an asynchronous source once its given *completion* operator completes.
If the *completion* fails the stream is failed with that exception.

For the corresponding operator for the Scala standard library `Future` see @ref:[futureSource](futureSource.md).

## Example

Suppose we are accessing a remote service that streams user data over HTTP/2 or a WebSocket. We can model that 
as a @apidoc[Source[User,NotUsed]] but that source will only be available once the connection has been established.

Java
: @@snip [CompletionStageSource.java](/akka-docs/src/test/java/jdocs/stream/operators/source/CompletionStageSource.java) { #sourceCompletionStageSource }

## Reactive Streams semantics

@@@div { .callout }

**emits** the next value from the asynchronous source, once its *completion operator* has completed

**completes** after the asynchronous source completes

@@@
