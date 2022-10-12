# mapAsync

Pass incoming elements to a function that return a @scala[`Future`] @java[`CompletionStage`] result.

@ref[Asynchronous operators](../index.md#asynchronous-operators)

## Signature

@apidoc[Source.mapAsync](Source) { scala="#mapAsync[T](parallelism:Int)(f:Out=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#mapAsync(int,akka.japi.function.Function)" }
@apidoc[Flow.mapAsync](Flow) { scala="#mapAsync[T](parallelism:Int)(f:Out=&gt;scala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#mapAsync(int,akka.japi.function.Function)" }


## Description

Pass incoming elements to a function that return a @scala[`Future`] @java[`CompletionStage`] result. When the @scala[`Future`] @java[`CompletionStage`] arrives the result is passed
downstream. Up to `n` elements can be processed concurrently, but regardless of their completion time the incoming
order will be kept when results complete. For use cases where order does not matter `mapAsyncUnordered` can be used.

If a @scala[`Future`] @java[`CompletionStage`] completes with `null`, it is ignored and the next element is processed.
If a @scala[`Future`] @java[`CompletionStage`] fails, the stream also fails (unless a different supervision strategy is applied)

## Examples

Imagine you are consuming messages from a broker. These messages represent business events produced on a service upstream. In that case, you want to consume the messages in order and one at a time:

Scala
:   @@snip [MapAsyncs.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/MapAsyncs.scala) { #mapasync-strict-order }

Java
:   @@snip [MapAsyncs.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/MapAsyncs.java) { #mapasync-strict-order }

When running the stream above the logging output would look like:

```
[...]
Processing event number Event(33)...
Completed processing 33
`mapAsync` emitted event number: 33
Processing event number Event(34)...
Completed processing 34
`mapAsync` emitted event number: 34
[...]
``` 

If, instead, you may process information concurrently, but still emit the messages downstream in order, you may increase the parallelism. In this case, the events could some IoT payload with weather metrics, for example, where processing the data in strict ordering is not critical:

Scala
:   @@snip [MapAsyncs.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/MapAsyncs.scala) { #mapasync-concurrent }

Java
:   @@snip [MapAsyncs.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/MapAsyncs.java) { #mapasync-concurrent }

In this case, the logging soon shows how processing of the events happens concurrently which may break the ordering. Still, the stage  emits the events back in the correct order:

```
[...]
Processing event number Event(15)...
Processing event number Event(16)...
Completed processing 16
Processing event number Event(17)...
Completed processing 17
Completed processing 15
`mapAsync` emitted event number: 15
`mapAsync` emitted event number: 16
Processing event number Event(18)...
`mapAsync` emitted event number: 17
[...]
```

See also @ref[mapAsyncUnordered](mapAsyncUnordered.md#examples).

## Reactive Streams semantics

@@@div { .callout }

**emits** when the @scala[`Future`] @java[`CompletionStage`] returned by the provided function finishes for the next element in sequence

**backpressures** when the number of @scala[`Future` s] @java[`CompletionStage` s] reaches the configured parallelism and the downstream backpressures

**completes** when upstream completes and all @scala[`Future` s] @java[`CompletionStage` s] has been completed and all elements has been emitted

@@@

