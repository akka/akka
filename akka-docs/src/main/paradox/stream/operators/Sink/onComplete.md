# Sink.onComplete

Invoke a callback when the stream has completed or failed.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.onComplete](Sink$) { scala="#onComplete[T](callback:scala.util.Try[akka.Done]=&gt;Unit):akka.stream.scaladsl.Sink[T,akka.NotUsed]" java="#onComplete(akka.japi.function.Procedure)" }


## Description

Invoke a callback when the stream has completed or failed.

## Reactive Streams semantics

@@@div { .callout }

**cancels** never

**backpressures** never

@@@


