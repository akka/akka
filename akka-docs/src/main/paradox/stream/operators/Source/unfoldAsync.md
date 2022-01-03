# Source.unfoldAsync

Just like `unfold` but the fold function returns a @scala[`Future`] @java[`CompletionStage`].

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.unfoldAsync](Source$) { scala="#unfoldAsync[S,E](s:S)(f:S=&gt;scala.concurrent.Future[Option[(S,E)]]):akka.stream.scaladsl.Source[E,akka.NotUsed]" java="#unfoldAsync(java.lang.Object,akka.japi.function.Function)" }


## Description

Just like `unfold` but the fold function returns a @scala[`Future`] @java[`CompletionStage`] which will cause the source to
complete or emit when it completes.

Can be used to implement many stateful sources without having to touch the more low level @ref[`GraphStage`](../../stream-customize.md) API.

## Examples

In this example we are asking an imaginary actor for chunks of bytes from an offset with a protocol like this: 

Scala
:   @@snip [UnfoldAsync.scala](/akka-docs/src/test/scala/docs/stream/operators/source/UnfoldAsync.scala) { #unfoldAsync-actor-protocol }

Java
:   @@snip [UnfoldAsync.java](/akka-docs/src/test/java/jdocs/stream/operators/source/UnfoldAsync.java) { #unfoldAsync-actor-protocol }


The actor will reply with the `Chunk` message, if we ask for an offset outside of the end of the data the actor will respond with an empty `ByteString`

We want to represent this as a stream of `ByteString`s that complete when we reach the end, to achieve this we use the offset as the state passed between `unfoldAsync` invocations:

Scala
:   @@snip [UnfoldAsync.scala](/akka-docs/src/test/scala/docs/stream/operators/source/UnfoldAsync.scala) { #unfoldAsync }

Java
:   @@snip [UnfoldAsync.java](/akka-docs/src/test/java/jdocs/stream/operators/source/UnfoldAsync.java) { #unfoldAsync }


## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and unfold state returned future completes with some value

**completes** when the @scala[future] @java[CompletionStage] returned by the unfold function completes with an empty value

@@@

