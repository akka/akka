# mapAsyncUnordered

Like `mapAsync` but @scala[`Future`] @java[`CompletionStage`] results are passed downstream as they arrive regardless of the order of the elements that triggered them.

@ref[Asynchronous operators](../index.md#asynchronous-operators)

## Signature

@apidoc[Source.mapAsyncUnordered](Source) { scala="#mapAsyncUnordered[T](parallelism:Int)(f:Out=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#mapAsyncUnordered(int,akka.japi.function.Function)" }
@apidoc[Flow.mapAsyncUnordered](Flow) { scala="#mapAsyncUnordered[T](parallelism:Int)(f:Out=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#mapAsyncUnordered(int,akka.japi.function.Function)" }


## Description

Like `mapAsync` but @scala[`Future`] @java[`CompletionStage`] results are passed downstream as they arrive regardless of the order of the elements
that triggered them.

If a @scala[`Future`] @java[`CompletionStage`] completes with `null`, it is ignored and the next element is processed.
If a @scala[`Future`] @java[`CompletionStage`] fails, the stream also fails (unless a different supervision strategy is applied)

## Examples

Imagine you are consuming messages from a source, and you prioritize throughput over order (this could be uncorrelated messages so order is irrelevant). You may use the `mapAsyncUnordered` (so messages are emitted as soon as they've been processed) with some parallelism (so processing happens concurrently)  :

Scala
:   @@snip [MapAsyncs.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/MapAsyncs.scala) { #mapasyncunordered }

Java
:   @@snip [MapAsyncs.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/MapAsyncs.java) { #mapasyncunordered }

When running the stream above the logging output would look like:

```
[...]
Processing event numner Event(27)...
Completed processing 27
`mapAsyncUnordered` emitted event number: 27
Processing event numner Event(28)...
Completed processing 22
`mapAsyncUnordered` emitted event number: 22
Processing event numner Event(29)...
Completed processing 26
`mapAsyncUnordered` emitted event number: 26
Processing event numner Event(30)...
Completed processing 30
`mapAsyncUnordered` emitted event number: 30
Processing event numner Event(31)...
Completed processing 31
`mapAsyncUnordered` emitted event number: 31
[...]
``` 

See @ref[mapAsync](mapAsync.md#examples) for a variant with ordering guarantees.

## Reactive Streams semantics

@@@div { .callout }

**emits** any of the @scala[`Future` s] @java[`CompletionStage` s] returned by the provided function complete

**backpressures** when the number of @scala[`Future` s] @java[`CompletionStage` s] reaches the configured parallelism and the downstream backpressures

**completes** upstream completes and all @scala[`Future` s] @java[`CompletionStage` s] has been completed  and all elements has been emitted

@@@

