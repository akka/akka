# Operators

## Source stages

These built-in sources are available from @scala[`akka.stream.scaladsl.Source`] @java[`akka.stream.javadsl.Source`]:

| |Operator|Description|
|--|--|--|
|Source|@ref[actorRef](Source/actorRef.md)|Materialize an `ActorRef`, sending messages to it will emit them on the stream. |
|Source|@ref[asSubscriber](Source/asSubscriber.md)|Integration with Reactive Streams, materializes into a `org.reactivestreams.Subscriber`.|
|Source|@ref[combine](Source/combine.md)|Combine several sources, using a given strategy such as merge or concat, into one source.|
|Source|@ref[cycle](Source/cycle.md)|Stream iterator in cycled manner.|
|Source|@ref[empty](Source/empty.md)|Complete right away without ever emitting any elements.|
|Source|@ref[failed](Source/failed.md)|Fail directly with a user specified exception.|
|Source|@ref[from](Source/from.md)|Stream the values of an `Iterable`.|
|Source|@ref[fromCompletionStage](Source/fromCompletionStage.md)|Send the single value of the `CompletionStage` when it completes and there is demand.|
|Source|@ref[fromFuture](Source/fromFuture.md)|Send the single value of the `Future` when it completes and there is demand.|
|Source|@ref[fromFutureSource](Source/fromFutureSource.md)|Streams the elements of the given future source once it successfully completes.|
|Source|@ref[fromIterator](Source/fromIterator.md)|Stream the values from an `Iterator`, requesting the next value when there is demand.|
|Source|@ref[fromPublisher](Source/fromPublisher.md)|Integration with Reactive Streams, subscribes to a `org.reactivestreams.Publisher`.|
|Source|@ref[fromSourceCompletionStage](Source/fromSourceCompletionStage.md)|Streams the elements of an asynchronous source once its given *completion* stage completes.|
|Source|@ref[lazily](Source/lazily.md)|Defers creation and materialization of a `Source` until there is demand.|
|Source|@ref[lazilyAsync](Source/lazilyAsync.md)|Defers creation and materialization of a `CompletionStage` until there is demand.|
|Source|@ref[maybe](Source/maybe.md)|Materialize a @scala[`Promise[Option[T]]`] @java[`CompletionStage`] that if completed with a @scala[`Some[T]`] @java[`Optional`] will emit that *T* and then complete the stream, or if completed with @scala[`None`] @java[`empty Optional`] complete the stream right away.|
|Source|@ref[queue](Source/queue.md)|Materialize a `SourceQueue` onto which elements can be pushed for emitting from the source. |
|Source|@ref[range](Source/range.md)|Emit each integer in a range, with an option to take bigger steps than 1.|
|Source|@ref[repeat](Source/repeat.md)|Stream a single object repeatedly|
|Source|@ref[single](Source/single.md)|Stream a single object|
|Source|@ref[tick](Source/tick.md)|A periodical repetition of an arbitrary object.|
|Source|@ref[unfold](Source/unfold.md)|Stream the result of a function as long as it returns a @scala[`Some`] @java[`Optional`].|
|Source|@ref[unfoldAsync](Source/unfoldAsync.md)|Just like `unfold` but the fold function returns a @scala[`Future`] @java[`CompletionStage`].|
|Source|@ref[unfoldResource](Source/unfoldResource.md)|Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.|
|Source|@ref[unfoldResourceAsync](Source/unfoldResourceAsync.md)|Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.|
|Source|@ref[zipN](Source/zipN.md)|Combine the elements of multiple streams into a stream of sequences.|
|Source|@ref[zipWithN](Source/zipWithN.md)|Combine the elements of multiple streams into a stream of sequences using a combiner function.|

## Sink stages

These built-in sinks are available from @scala[`akka.stream.scaladsl.Sink`] @java[`akka.stream.javadsl.Sink`]:


| |Operator|Description|
|--|--|--|
|Sink|@ref[actorRef](Sink/actorRef.md)|Send the elements from the stream to an `ActorRef`.|
|Sink|@ref[actorRefWithAck](Sink/actorRefWithAck.md)|Send the elements from the stream to an `ActorRef` which must then acknowledge reception after completing a message, to provide back pressure onto the sink.|
|Sink|@ref[asPublisher](Sink/asPublisher.md)|Integration with Reactive Streams, materializes into a `org.reactivestreams.Publisher`.|
|Sink|@ref[cancelled](Sink/cancelled.md)|Immediately cancel the stream|
|Sink|@ref[combine](Sink/combine.md)|Combine several sinks into one using a user specified strategy|
|Sink|@ref[fold](Sink/fold.md)|Fold over emitted element with a function, where each invocation will get the new element and the result from the previous fold invocation.|
|Sink|@ref[foreach](Sink/foreach.md)|Invoke a given procedure for each element received.|
|Sink|@ref[foreachParallel](Sink/foreachParallel.md)|Like `foreach` but allows up to `parallellism` procedure calls to happen in parallel.|
|Sink|@ref[fromSubscriber](Sink/fromSubscriber.md)|Integration with Reactive Streams, wraps a `org.reactivestreams.Subscriber` as a sink.|
|Sink|@ref[head](Sink/head.md)|Materializes into a @scala[`Future`] @java[`CompletionStage`] which completes with the first value arriving, after this the stream is canceled.|
|Sink|@ref[headOption](Sink/headOption.md)|Materializes into a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the first value arriving wrapped in @scala[`Some`] @java[`Optional`], or @scala[a `None`] @java[an empty Optional] if the stream completes without any elements emitted.|
|Sink|@ref[ignore](Sink/ignore.md)|Consume all elements but discards them.|
|Sink|@ref[last](Sink/last.md)|Materializes into a @scala[`Future`] @java[`CompletionStage`] which will complete with the last value emitted when the stream completes.|
|Sink|@ref[lastOption](Sink/lastOption.md)|Materialize a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the last value emitted wrapped in an @scala[`Some`] @java[`Optional`] when the stream completes.|
|Sink|@ref[lazyInitAsync](Sink/lazyInitAsync.md)|Creates a real `Sink` upon receiving the first element. |
|Sink|@ref[onComplete](Sink/onComplete.md)|Invoke a callback when the stream has completed or failed.|
|Sink|@ref[preMaterialize](Sink/preMaterialize.md)|Materializes this Sink, immediately returning (1) its materialized value, and (2) a new Sink that can be consume elements 'into' the pre-materialized one.|
|Sink|@ref[queue](Sink/queue.md)|Materialize a `SinkQueue` that can be pulled to trigger demand through the sink.|
|Sink|@ref[reduce](Sink/reduce.md)|Apply a reduction function on the incoming elements and pass the result to the next invocation.|
|Sink|@ref[seq](Sink/seq.md)|Collect values emitted from the stream into a collection.|

## Additional Sink and Source converters

Sources and sinks for integrating with `java.io.InputStream` and `java.io.OutputStream` can be found on
`StreamConverters`. As they are blocking APIs the implementations of these stages are run on a separate
dispatcher configured through the `akka.stream.blocking-io-dispatcher`.

@@@ warning

Be aware that `asInputStream` and `asOutputStream` materialize `InputStream` and `OutputStream` respectively as
blocking API implementation. They will block tread until data will be available from upstream.
Because of blocking nature these objects cannot be used in `mapMaterializeValue` section as it causes deadlock
of the stream materialization process.
For example, following snippet will fall with timeout exception:

```scala
...
.toMat(StreamConverters.asInputStream().mapMaterializedValue { inputStream ⇒
        inputStream.read()  // this could block forever
        ...
}).run()
```

@@@

| |Operator|Description|
|--|--|--|
|StreamConverters|@ref[asInputStream](StreamConverters/asInputStream.md)|Create a sink which materializes into an `InputStream` that can be read to trigger demand through the sink.|
|StreamConverters|@ref[asJavaStream](StreamConverters/asJavaStream.md)|Create a sink which materializes into Java 8 `Stream` that can be run to trigger demand through the sink.|
|StreamConverters|@ref[asOutputStream](StreamConverters/asOutputStream.md)|Create a source that materializes into an `OutputStream`.|
|StreamConverters|@ref[fromInputStream](StreamConverters/fromInputStream.md)|Create a source that wraps an `InputStream`.|
|StreamConverters|@ref[fromJavaStream](StreamConverters/fromJavaStream.md)|Create a source that wraps a Java 8 `Stream`.|
|StreamConverters|@ref[fromOutputStream](StreamConverters/fromOutputStream.md)|Create a sink that wraps an `OutputStream`.|
|StreamConverters|@ref[javaCollector](StreamConverters/javaCollector.md)|Create a sink which materializes into a @scala[`Future`] @java[`CompletionStage`] which will be completed with a result of the Java 8 `Collector` transformation and reduction operations.|
|StreamConverters|@ref[javaCollectorParallelUnordered](StreamConverters/javaCollectorParallelUnordered.md)|Create a sink which materializes into a @scala[`Future`] @java[`CompletionStage`] which will be completed with a result of the Java 8 `Collector` transformation and reduction operations.|

## File IO Sinks and Sources

Sources and sinks for reading and writing files can be found on `FileIO`.

| |Operator|Description|
|--|--|--|
|FileIO|@ref[fromPath](FileIO/fromPath.md)|Emit the contents of a file.|
|FileIO|@ref[toPath](FileIO/toPath.md)|Create a sink which will write incoming `ByteString` s to a given file path.|

## Simple processing stages

These stages can transform the rate of incoming elements since there are stages that emit multiple elements for a
single input (e.g. `mapConcat`) or consume multiple elements before emitting one output (e.g. `filter`).
However, these rate transformations are data-driven, i.e. it is the incoming elements that define how the
rate is affected. This is in contrast with [detached stages](#backpressure-aware-stages) which can change their processing behavior
depending on being backpressured by downstream or not.

| |Operator|Description|
|--|--|--|
|Source/Flow|@ref[alsoTo](Source-or-Flow/alsoTo.md)|Attaches the given `Sink` to this `Flow`, meaning that elements that pass through this `Flow` will also be sent to the `Sink`.|
|Source/Flow|@ref[collect](Source-or-Flow/collect.md)|Apply a partial function to each incoming element, if the partial function is defined for a value the returned value is passed downstream.|
|Source/Flow|@ref[collectType](Source-or-Flow/collectType.md)|Transform this stream by testing the type of each of the elements on which the element is an instance of the provided type as they pass through this processing step.|
|Source/Flow|@ref[detach](Source-or-Flow/detach.md)|Detach upstream demand from downstream demand without detaching the stream rates.|
|Source/Flow|@ref[divertTo](Source-or-Flow/divertTo.md)|Each upstream element will either be diverted to the given sink, or the downstream consumer according to the predicate function applied to the element.|
|Source/Flow|@ref[drop](Source-or-Flow/drop.md)|Drop `n` elements and then pass any subsequent element downstream.|
|Source/Flow|@ref[dropWhile](Source-or-Flow/dropWhile.md)|Drop elements as long as a predicate function return true for the element|
|Source/Flow|@ref[filter](Source-or-Flow/filter.md)|Filter the incoming elements using a predicate.|
|Source/Flow|@ref[filterNot](Source-or-Flow/filterNot.md)|Filter the incoming elements using a predicate.|
|Source/Flow|@ref[fold](Source-or-Flow/fold.md)|Start with current value `zero` and then apply the current and next value to the given function, when upstream complete the current value is emitted downstream.|
|Source/Flow|@ref[foldAsync](Source-or-Flow/foldAsync.md)|Just like `fold` but receiving a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.|
|Source/Flow|@ref[grouped](Source-or-Flow/grouped.md)|Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of elements downstream.|
|Source/Flow|@ref[intersperse](Source-or-Flow/intersperse.md)|Intersperse stream with provided element similar to `List.mkString`.|
|Flow|@ref[lazyInitAsync](Flow/lazyInitAsync.md)|Creates a real `Flow` upon receiving the first element by calling relevant `flowFactory` given as an argument.|
|Source/Flow|@ref[limit](Source-or-Flow/limit.md)|Limit number of element from upstream to given `max` number.|
|Source/Flow|@ref[limitWeighted](Source-or-Flow/limitWeighted.md)|Ensure stream boundedness by evaluating the cost of incoming elements using a cost function.|
|Source/Flow|@ref[log](Source-or-Flow/log.md)|Log elements flowing through the stream as well as completion and erroring.|
|Source/Flow|@ref[map](Source-or-Flow/map.md)|Transform each element in the stream by calling a mapping function with it and passing the returned value downstream.|
|Source/Flow|@ref[mapConcat](Source-or-Flow/mapConcat.md)|Transform each element into zero or more elements that are individually passed downstream.|
|Source/Flow|@ref[mapError](Source-or-Flow/mapError.md)|While similar to `recover` this stage can be used to transform an error signal to a different one *without* logging it as an error in the process.|
|Source/Flow|@ref[recover](Source-or-Flow/recover.md)|Allow sending of one last element downstream when a failure has happened upstream.|
|Source/Flow|@ref[recoverWith](Source-or-Flow/recoverWith.md)|Allow switching to alternative Source when a failure has happened upstream.|
|Source/Flow|@ref[recoverWithRetries](Source-or-Flow/recoverWithRetries.md)|RecoverWithRetries allows to switch to alternative Source on flow failure.|
|Source/Flow|@ref[reduce](Source-or-Flow/reduce.md)|Start with first element and then apply the current and next value to the given function, when upstream complete the current value is emitted downstream.|
|Source/Flow|@ref[scan](Source-or-Flow/scan.md)|Emit its current value which starts at `zero` and then applies the current and next value to the given function emitting the next current value.|
|Source/Flow|@ref[scanAsync](Source-or-Flow/scanAsync.md)|Just like `scan` but receiving a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.|
|Source/Flow|@ref[sliding](Source-or-Flow/sliding.md)|Provide a sliding window over the incoming stream and pass the windows as groups of elements downstream.|
|Source/Flow|@ref[statefulMapConcat](Source-or-Flow/statefulMapConcat.md)|Transform each element into zero or more elements that are individually passed downstream.|
|Source/Flow|@ref[take](Source-or-Flow/take.md)|Pass `n` incoming elements downstream and then complete|
|Source/Flow|@ref[takeWhile](Source-or-Flow/takeWhile.md)|Pass elements downstream as long as a predicate function return true for the element include the element when the predicate first return false and then complete.|
|Source/Flow|@ref[throttle](Source-or-Flow/throttle.md)|Limit the throughput to a specific number of elements per time unit, or a specific total cost per time unit, where a function has to be provided to calculate the individual cost of each element.|
|Source/Flow|@ref[watch](Source-or-Flow/watch.md)|Watch a specific `ActorRef` and signal a failure downstream once the actor terminates.|
|Source/Flow|@ref[wireTap](Source-or-Flow/wireTap.md)|Attaches the given `Sink` to this `Flow` as a wire tap, meaning that elements that pass through will also be sent to the wire-tap `Sink`, without the latter affecting the mainline flow.|

## Flow stages composed of Sinks and Sources



| |Operator|Description|
|--|--|--|
|Flow|@ref[fromSinkAndSource](Flow/fromSinkAndSource.md)|Creates a `Flow` from a `Sink` and a `Source` where the Flow's input will be sent to the `Sink` and the `Flow` 's output will come from the Source.|
|Flow|@ref[fromSinkAndSourceCoupled](Flow/fromSinkAndSourceCoupled.md)|Allows coupling termination (cancellation, completion, erroring) of Sinks and Sources while creating a Flow between them.|

## Asynchronous processing stages

These stages encapsulate an asynchronous computation, properly handling backpressure while taking care of the asynchronous
operation at the same time (usually handling the completion of a @scala[`Future`] @java[`CompletionStage`]).

| |Operator|Description|
|--|--|--|
|Source/Flow|@ref[ask](Source-or-Flow/ask.md)|Use the `ask` pattern to send a request-reply message to the target `ref` actor.|
|Source/Flow|@ref[mapAsync](Source-or-Flow/mapAsync.md)|Pass incoming elements to a function that return a @scala[`Future`] @java[`CompletionStage`] result.|
|Source/Flow|@ref[mapAsyncUnordered](Source-or-Flow/mapAsyncUnordered.md)|Like `mapAsync` but @scala[`Future`] @java[`CompletionStage`] results are passed downstream as they arrive regardless of the order of the elements that triggered them.|

## Timer driven stages

These stages process elements using timers, delaying, dropping or grouping elements for certain time durations.

| |Operator|Description|
|--|--|--|
|Source/Flow|@ref[delay](Source-or-Flow/delay.md)|Delay every element passed through with a specific duration.|
|Source/Flow|@ref[dropWithin](Source-or-Flow/dropWithin.md)|Drop elements until a timeout has fired|
|Source/Flow|@ref[groupedWeightedWithin](Source-or-Flow/groupedWeightedWithin.md)|Chunk up this stream into groups of elements received within a time window, or limited by the weight of the elements, whatever happens first.|
|Source/Flow|@ref[groupedWithin](Source-or-Flow/groupedWithin.md)|Chunk up this stream into groups of elements received within a time window, or limited by the number of the elements, whatever happens first.|
|Source/Flow|@ref[initialDelay](Source-or-Flow/initialDelay.md)|Delays the initial element by the specified duration.|
|Source/Flow|@ref[takeWithin](Source-or-Flow/takeWithin.md)|Pass elements downstream within a timeout and then complete.|

## Backpressure aware stages

These stages are aware of the backpressure provided by their downstreams and able to adapt their behavior to that signal.

| |Operator|Description|
|--|--|--|
|Source/Flow|@ref[batch](Source-or-Flow/batch.md)|Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there is backpressure and a maximum number of batched elements is not yet reached.|
|Source/Flow|@ref[batchWeighted](Source-or-Flow/batchWeighted.md)|Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there is backpressure and a maximum weight batched elements is not yet reached.|
|Source/Flow|@ref[buffer](Source-or-Flow/buffer.md)|Allow for a temporarily faster upstream events by buffering `size` elements.|
|Source/Flow|@ref[conflate](Source-or-Flow/conflate.md)|Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there is backpressure.|
|Source/Flow|@ref[conflateWithSeed](Source-or-Flow/conflateWithSeed.md)|Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there is backpressure.|
|Source/Flow|@ref[expand](Source-or-Flow/expand.md)|Like `extrapolate`, but does not have the `initial` argument, and the `Iterator` is also used in lieu of the original element, allowing for it to be rewritten and/or filtered.|
|Source/Flow|@ref[extrapolate](Source-or-Flow/extrapolate.md)|Allow for a faster downstream by expanding the last emitted element to an `Iterator`.|

## Nesting and flattening stages

These stages either take a stream and turn it into a stream of streams (nesting) or they take a stream that contains
nested streams and turn them into a stream of elements instead (flattening).

See the [Substreams](stream-substream.md) page for more detail and code samples.

| |Operator|Description|
|--|--|--|
|Source/Flow|@ref[flatMapConcat](Source-or-Flow/flatMapConcat.md)|Transform each input element into a `Source` whose elements are then flattened into the output stream through concatenation.|
|Source/Flow|@ref[flatMapMerge](Source-or-Flow/flatMapMerge.md)|Transform each input element into a `Source` whose elements are then flattened into the output stream through merging.|
|Source/Flow|@ref[groupBy](Source-or-Flow/groupBy.md)|Demultiplex the incoming stream into separate output streams.|
|Source/Flow|@ref[prefixAndTail](Source-or-Flow/prefixAndTail.md)|Take up to *n* elements from the stream (less than *n* only if the upstream completes before emitting *n* elements) and returns a pair containing a strict sequence of the taken element and a stream representing the remaining elements.|
|Source/Flow|@ref[splitAfter](Source-or-Flow/splitAfter.md)|End the current substream whenever a predicate returns `true`, starting a new substream for the next element.|
|Source/Flow|@ref[splitWhen](Source-or-Flow/splitWhen.md)|Split off elements into a new substream whenever a predicate function return `true`.|

## Time aware stages

Those stages operate taking time into consideration.

| |Operator|Description|
|--|--|--|
|Source/Flow|@ref[backpressureTimeout](Source-or-Flow/backpressureTimeout.md)|If the time between the emission of an element and the following downstream demand exceeds the provided timeout, the stream is failed with a `TimeoutException`.|
|Source/Flow|@ref[completionTimeout](Source-or-Flow/completionTimeout.md)|If the completion of the stream does not happen until the provided timeout, the stream is failed with a `TimeoutException`.|
|Source/Flow|@ref[idleTimeout](Source-or-Flow/idleTimeout.md)|If the time between two processed elements exceeds the provided timeout, the stream is failed with a `TimeoutException`.|
|Source/Flow|@ref[initialTimeout](Source-or-Flow/initialTimeout.md)|If the first element has not passed through this stage before the provided timeout, the stream is failed with a `TimeoutException`.|
|Source/Flow|@ref[keepAlive](Source-or-Flow/keepAlive.md)|Injects additional (configured) elements if upstream does not emit for a configured amount of time.|

## Fan-in stages

These stages take multiple streams as their input and provide a single output combining the elements from all of
the inputs in different ways.

| |Operator|Description|
|--|--|--|
|Source/Flow|@ref[concat](Source-or-Flow/concat.md)|After completion of the original upstream the elements of the given source will be emitted.|
|Source/Flow|@ref[interleave](Source-or-Flow/interleave.md)|Emits a specifiable number of elements from the original source, then from the provided source and repeats.|
|Source/Flow|@ref[merge](Source-or-Flow/merge.md)|Merge multiple sources.|
|Source/Flow|@ref[mergeSorted](Source-or-Flow/mergeSorted.md)|Merge multiple sources.|
|Source/Flow|@ref[orElse](Source-or-Flow/orElse.md)|If the primary source completes without emitting any elements, the elements from the secondary source are emitted.|
|Source/Flow|@ref[prepend](Source-or-Flow/prepend.md)|Prepends the given source to the flow, consuming it until completion before the original source is consumed.|
|Source/Flow|@ref[zip](Source-or-Flow/zip.md)|Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream.|
|Source/Flow|@ref[zipWith](Source-or-Flow/zipWith.md)|Combines elements from multiple sources through a `combine` function and passes the returned value downstream.|
|Source/Flow|@ref[zipWithIndex](Source-or-Flow/zipWithIndex.md)|Zips elements of current flow with its indices.|

## Watching status stages



| |Operator|Description|
|--|--|--|
|Source/Flow|@ref[monitor](Source-or-Flow/monitor.md)|Materializes to a `FlowMonitor` that monitors messages flowing through or completion of the stage.|
|Source/Flow|@ref[watchTermination](Source-or-Flow/watchTermination.md)|Materializes to a @scala[`Future`] @java[`CompletionStage`] that will be completed with Done or failed depending whether the upstream of the stage has been completed or failed.|

@@@ index

* [combine](Source/combine.md)
* [fromPublisher](Source/fromPublisher.md)
* [fromIterator](Source/fromIterator.md)
* [cycle](Source/cycle.md)
* [fromFuture](Source/fromFuture.md)
* [fromCompletionStage](Source/fromCompletionStage.md)
* [fromFutureSource](Source/fromFutureSource.md)
* [fromSourceCompletionStage](Source/fromSourceCompletionStage.md)
* [tick](Source/tick.md)
* [single](Source/single.md)
* [repeat](Source/repeat.md)
* [unfold](Source/unfold.md)
* [unfoldAsync](Source/unfoldAsync.md)
* [empty](Source/empty.md)
* [maybe](Source/maybe.md)
* [failed](Source/failed.md)
* [lazily](Source/lazily.md)
* [lazilyAsync](Source/lazilyAsync.md)
* [asSubscriber](Source/asSubscriber.md)
* [actorRef](Source/actorRef.md)
* [zipN](Source/zipN.md)
* [zipWithN](Source/zipWithN.md)
* [queue](Source/queue.md)
* [unfoldResource](Source/unfoldResource.md)
* [unfoldResourceAsync](Source/unfoldResourceAsync.md)
* [from](Source/from.md)
* [range](Source/range.md)
* [concat](Source-or-Flow/concat.md)
* [prepend](Source-or-Flow/prepend.md)
* [orElse](Source-or-Flow/orElse.md)
* [alsoTo](Source-or-Flow/alsoTo.md)
* [divertTo](Source-or-Flow/divertTo.md)
* [wireTap](Source-or-Flow/wireTap.md)
* [interleave](Source-or-Flow/interleave.md)
* [merge](Source-or-Flow/merge.md)
* [mergeSorted](Source-or-Flow/mergeSorted.md)
* [zip](Source-or-Flow/zip.md)
* [zipWith](Source-or-Flow/zipWith.md)
* [zipWithIndex](Source-or-Flow/zipWithIndex.md)
* [map](Source-or-Flow/map.md)
* [recover](Source-or-Flow/recover.md)
* [mapError](Source-or-Flow/mapError.md)
* [recoverWith](Source-or-Flow/recoverWith.md)
* [recoverWithRetries](Source-or-Flow/recoverWithRetries.md)
* [mapConcat](Source-or-Flow/mapConcat.md)
* [statefulMapConcat](Source-or-Flow/statefulMapConcat.md)
* [mapAsync](Source-or-Flow/mapAsync.md)
* [mapAsyncUnordered](Source-or-Flow/mapAsyncUnordered.md)
* [ask](Source-or-Flow/ask.md)
* [watch](Source-or-Flow/watch.md)
* [filter](Source-or-Flow/filter.md)
* [filterNot](Source-or-Flow/filterNot.md)
* [collect](Source-or-Flow/collect.md)
* [collectType](Source-or-Flow/collectType.md)
* [grouped](Source-or-Flow/grouped.md)
* [limit](Source-or-Flow/limit.md)
* [limitWeighted](Source-or-Flow/limitWeighted.md)
* [sliding](Source-or-Flow/sliding.md)
* [scan](Source-or-Flow/scan.md)
* [scanAsync](Source-or-Flow/scanAsync.md)
* [fold](Source-or-Flow/fold.md)
* [foldAsync](Source-or-Flow/foldAsync.md)
* [reduce](Source-or-Flow/reduce.md)
* [intersperse](Source-or-Flow/intersperse.md)
* [groupedWithin](Source-or-Flow/groupedWithin.md)
* [groupedWeightedWithin](Source-or-Flow/groupedWeightedWithin.md)
* [delay](Source-or-Flow/delay.md)
* [drop](Source-or-Flow/drop.md)
* [dropWithin](Source-or-Flow/dropWithin.md)
* [takeWhile](Source-or-Flow/takeWhile.md)
* [dropWhile](Source-or-Flow/dropWhile.md)
* [take](Source-or-Flow/take.md)
* [takeWithin](Source-or-Flow/takeWithin.md)
* [conflateWithSeed](Source-or-Flow/conflateWithSeed.md)
* [conflate](Source-or-Flow/conflate.md)
* [batch](Source-or-Flow/batch.md)
* [batchWeighted](Source-or-Flow/batchWeighted.md)
* [expand](Source-or-Flow/expand.md)
* [extrapolate](Source-or-Flow/extrapolate.md)
* [buffer](Source-or-Flow/buffer.md)
* [prefixAndTail](Source-or-Flow/prefixAndTail.md)
* [groupBy](Source-or-Flow/groupBy.md)
* [splitWhen](Source-or-Flow/splitWhen.md)
* [splitAfter](Source-or-Flow/splitAfter.md)
* [flatMapConcat](Source-or-Flow/flatMapConcat.md)
* [flatMapMerge](Source-or-Flow/flatMapMerge.md)
* [initialTimeout](Source-or-Flow/initialTimeout.md)
* [completionTimeout](Source-or-Flow/completionTimeout.md)
* [idleTimeout](Source-or-Flow/idleTimeout.md)
* [backpressureTimeout](Source-or-Flow/backpressureTimeout.md)
* [keepAlive](Source-or-Flow/keepAlive.md)
* [throttle](Source-or-Flow/throttle.md)
* [detach](Source-or-Flow/detach.md)
* [watchTermination](Source-or-Flow/watchTermination.md)
* [monitor](Source-or-Flow/monitor.md)
* [initialDelay](Source-or-Flow/initialDelay.md)
* [log](Source-or-Flow/log.md)
* [fromSinkAndSource](Flow/fromSinkAndSource.md)
* [fromSinkAndSourceCoupled](Flow/fromSinkAndSourceCoupled.md)
* [lazyInitAsync](Flow/lazyInitAsync.md)
* [preMaterialize](Sink/preMaterialize.md)
* [fromSubscriber](Sink/fromSubscriber.md)
* [cancelled](Sink/cancelled.md)
* [head](Sink/head.md)
* [headOption](Sink/headOption.md)
* [last](Sink/last.md)
* [lastOption](Sink/lastOption.md)
* [seq](Sink/seq.md)
* [asPublisher](Sink/asPublisher.md)
* [ignore](Sink/ignore.md)
* [foreach](Sink/foreach.md)
* [combine](Sink/combine.md)
* [foreachParallel](Sink/foreachParallel.md)
* [fold](Sink/fold.md)
* [reduce](Sink/reduce.md)
* [onComplete](Sink/onComplete.md)
* [actorRef](Sink/actorRef.md)
* [actorRefWithAck](Sink/actorRefWithAck.md)
* [queue](Sink/queue.md)
* [lazyInitAsync](Sink/lazyInitAsync.md)
* [fromInputStream](StreamConverters/fromInputStream.md)
* [asOutputStream](StreamConverters/asOutputStream.md)
* [fromOutputStream](StreamConverters/fromOutputStream.md)
* [asInputStream](StreamConverters/asInputStream.md)
* [javaCollector](StreamConverters/javaCollector.md)
* [javaCollectorParallelUnordered](StreamConverters/javaCollectorParallelUnordered.md)
* [asJavaStream](StreamConverters/asJavaStream.md)
* [fromJavaStream](StreamConverters/fromJavaStream.md)
* [fromPath](FileIO/fromPath.md)
* [toPath](FileIO/toPath.md)

@@@
