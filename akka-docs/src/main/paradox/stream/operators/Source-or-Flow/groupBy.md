# groupBy

Demultiplex the incoming stream into separate output streams.

@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)

## Signature

@apidoc[Source.groupBy](Source) { scala="#groupBy[K](maxSubstreams:Int,f:Out=&gt;K):akka.stream.scaladsl.SubFlow[Out,Mat,FlowOps.this.Repr,FlowOps.this.Closed]" java="#groupBy(int,akka.japi.function.Function,boolean)" }
@apidoc[Flow.groupBy](Flow) { scala="#groupBy[K](maxSubstreams:Int,f:Out=&gt;K):akka.stream.scaladsl.SubFlow[Out,Mat,FlowOps.this.Repr,FlowOps.this.Closed]" java="#groupBy(int,akka.japi.function.Function,boolean)" }


## Description

This operation demultiplexes the incoming stream into separate output streams, one for each element key. The
key is computed for each element using the given function. When a new key is encountered for the first time
a new substream is opened and subsequently fed with all elements belonging to that key.

Note: If `allowClosedSubstreamRecreation` is set to `true` substream completion and incoming
elements are subject to race-conditions. If elements arrive for a stream that is in the process
of closing these elements might get lost.

@@@ warning

If `allowClosedSubstreamRecreation` is set to `false` (default behavior) the operators keeps track of all
keys of streams that have already been closed. If you expect an infinite number of keys this can cause
memory issues. Elements belonging to those keys are drained directly and not send to the substream.

@@@

## Example

Scala
:   @@snip [GroupBy.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/GroupBy.scala) { #groupBy }

Java
:   @@snip [GroupBy.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #groupBy }

Note: groupBy operator is used to partition the stream based on an identifier which is not same as using async boundary in the stream which is used for running the same stream concurrently. If a stream can be exclusively partitioned, it can be executed efficiently, by maximizing the parallel processing using groupBy operator.

Example with async boundary that introduces running concurrently :

Scala
:   @@snip [GroupBy.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/GroupBy.scala) { #groupByWithAsync }

Java
:   @@snip [GroupBy.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #groupByWithAsync }


## Reactive Streams semantics

@@@div { .callout }

**emits** an element for which the grouping function returns a group that has not yet been created. Emits the new group
there is an element pending for a group whose substream backpressures

**completes** when upstream completes (Until the end of stream it is not possible to know whether new substreams will be needed or not)

@@@

