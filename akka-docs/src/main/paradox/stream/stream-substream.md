# Substreams

## Dependency

To use Akka Streams, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  symbol1=AkkaVersion
  value1="$akka.version$"
  group="com.typesafe.akka"
  artifact="akka-stream_$scala.binary.version$"
  version=AkkaVersion
}

## Introduction

Substreams are represented as `SubSource` or `SubFlow` instances, on which you can multiplex a single `Source` or `Flow`
into a stream of streams.

SubFlows cannot contribute to the super-flow’s materialized value since they are materialized later,
during the runtime of the stream processing.

operators that create substreams are listed on @ref[Nesting and flattening operators](operators/index.md#nesting-and-flattening-operators)

## Nesting operators

### groupBy

A typical operation that generates substreams is `groupBy`.

Scala
:   @@snip [SubstreamDocSpec.scala](/akka-docs/src/test/scala/docs/stream/SubstreamDocSpec.scala) { #groupBy1 }

Java
:   @@snip [SubstreamDocTest.java](/akka-docs/src/test/java/jdocs/stream/SubstreamDocTest.java) { #groupBy1 }

![stream-substream-groupBy1.png](../../images/stream-substream-groupBy1.png)

This operation splits the incoming stream into separate output
streams, one for each element key. The key is computed for each element
using the given function, which is `f` in the above diagram. When a new key is encountered for the first time
a new substream is opened and subsequently fed with all elements belonging to that key.
If `allowClosedSubstreamRecreation` is set to `true` a substream belonging to a specific key
will be recreated if it was closed before, otherwise elements belonging to that key will be dropped.

If you add a `Sink` or `Flow` right after the `groupBy` operator,
all transformations are applied to all encountered substreams in the same fashion.
So, if you add the following `Sink`, that is added to each of the substreams as in the below diagram.

Scala
:   @@snip [SubstreamDocSpec.scala](/akka-docs/src/test/scala/docs/stream/SubstreamDocSpec.scala) { #groupBy2 }

Java
:   @@snip [SubstreamDocTest.java](/akka-docs/src/test/java/jdocs/stream/SubstreamDocTest.java) { #groupBy2 }

![stream-substream-groupBy2.png](../../images/stream-substream-groupBy2.png)

Also substreams, more precisely, `SubFlow` and `SubSource` have methods that allow you to
merge or concat substreams into the main stream again.

The `mergeSubstreams` method merges an unbounded number of substreams back to the main stream.

Scala
:   @@snip [SubstreamDocSpec.scala](/akka-docs/src/test/scala/docs/stream/SubstreamDocSpec.scala) { #groupBy3 }

Java
:   @@snip [SubstreamDocTest.java](/akka-docs/src/test/java/jdocs/stream/SubstreamDocTest.java) { #groupBy3 }

![stream-substream-groupBy3.png](../../images/stream-substream-groupBy3.png)

You can limit the number of active substreams running and being merged at a time,
with either the `mergeSubstreamsWithParallelism` or `concatSubstreams` method.

Scala
:   @@snip [SubstreamDocSpec.scala](/akka-docs/src/test/scala/docs/stream/SubstreamDocSpec.scala) { #groupBy4 }

Java
:   @@snip [SubstreamDocTest.java](/akka-docs/src/test/java/jdocs/stream/SubstreamDocTest.java) { #groupBy4 }

However, since the number of running (i.e. not yet completed) substreams is capped,
be careful so that these methods do not cause deadlocks with back pressure like in the below diagram.

Element one and two leads to two created substreams, but since the number of substreams are capped to 2 
when element 3 comes in it cannot lead to creation of a new substream until one of the previous two are completed 
and this leads to the stream being deadlocked.

![stream-substream-groupBy4.png](../../images/stream-substream-groupBy4.png)

### splitWhen and splitAfter

`splitWhen` and `splitAfter` are two other operations which generate substreams.

The difference from `groupBy` is that, if the predicate for `splitWhen` and `splitAfter` returns true,
a new substream is generated, and the succeeding elements after split will flow into the new substream.

`splitWhen` flows the element on which the predicate returned true to a new substream,
 whereas `splitAfter` flows the next element to the new substream after the element on which predicate returned true.

Scala
:   @@snip [SubstreamDocSpec.scala](/akka-docs/src/test/scala/docs/stream/SubstreamDocSpec.scala) { #splitWhenAfter }

Java
:   @@snip [SubstreamDocTest.java](/akka-docs/src/test/java/jdocs/stream/SubstreamDocTest.java) { #splitWhenAfter }

These are useful when you scanned over something and you don't need to care about anything behind it.
A typical example is counting the number of characters for each line like below.

Scala
:   @@snip [SubstreamDocSpec.scala](/akka-docs/src/test/scala/docs/stream/SubstreamDocSpec.scala) { #wordCount }

Java
:   @@snip [SubstreamDocTest.java](/akka-docs/src/test/java/jdocs/stream/SubstreamDocTest.java) { #wordCount }

This prints out the following output.

```
23
16
26
``` 

![stream-substream-splitWhen-splitAfter.png](../../images/stream-substream-splitWhen-splitAfter.png)

## Flattening operators

### flatMapConcat

`flatMapConcat` and `flatMapMerge` are substream operations different from `groupBy` and `splitWhen/After`.

`flatMapConcat` takes a function, which is `f` in the following diagram.
The function `f` of `flatMapConcat` transforms each input element into a `Source` that is then flattened
into the output stream by concatenation.

Scala
:   @@snip [SubstreamDocSpec.scala](/akka-docs/src/test/scala/docs/stream/SubstreamDocSpec.scala) { #flatMapConcat }

Java
:   @@snip [SubstreamDocTest.java](/akka-docs/src/test/java/jdocs/stream/SubstreamDocTest.java) { #flatMapConcat }

![stream-substream-flatMapConcat1.png](../../images/stream-substream-flatMapConcat1.png)

Like the `concat` operation on `Flow`, it fully consumes one `Source` after the other.
So, there is only one substream actively running at a given time.

Then once the active substream is fully consumed, the next substream can start running.
Elements from all the substreams are concatenated to the sink.

![stream-substream-flatMapConcat2.png](../../images/stream-substream-flatMapConcat2.png)

### flatMapMerge

`flatMapMerge` is similar to `flatMapConcat`, but it doesn't wait for one `Source` to be fully consumed.
 Instead, up to `breadth` number of streams emit elements at any given time.

Scala
:   @@snip [SubstreamDocSpec.scala](/akka-docs/src/test/scala/docs/stream/SubstreamDocSpec.scala) { #flatMapMerge }

Java
:   @@snip [SubstreamDocTest.java](/akka-docs/src/test/java/jdocs/stream/SubstreamDocTest.java) { #flatMapMerge }

![stream-substream-flatMapMerge.png](../../images/stream-substream-flatMapMerge.png)
