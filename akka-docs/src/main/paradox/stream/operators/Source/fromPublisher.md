# Source.fromPublisher

Integration with Reactive Streams, subscribes to a @javadoc[Publisher](java.util.concurrent.Flow.Publisher).

@ref[Source operators](../index.md#source-operators)

## Signature

Scala
:   @@snip[JavaFlowSupport.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/JavaFlowSupport.scala) { #fromPublisher }

Java
:   @@snip[JavaFlowSupport.java](/akka-docs/src/test/java-jdk9-only/jdocs/stream/operators/source/FromPublisher.java) { #api }


## Description

If you want to create a @apidoc[Source] that gets its elements from another library that supports
[Reactive Streams](https://www.reactive-streams.org/), you can use `JavaFlowSupport.Source.fromPublisher`.
This source will produce the elements from the @javadoc[Publisher](java.util.concurrent.Flow.Publisher),
and coordinate backpressure as needed.

If the API you want to consume elements from accepts a @javadoc[Subscriber](java.util.concurrent.Flow.Subscriber) instead of providing a @javadoc[Publisher](java.util.concurrent.Flow.Publisher), see @ref[asSubscriber](asSubscriber.md).

@@@ note

For JDK 8 users: since @javadoc[java.util.concurrent.Flow](java.util.concurrent.Flow) was introduced in JDK version 9,
if you are still on version 8 you may use the [org.reactivestreams](https://github.com/reactive-streams/reactive-streams-jvm#reactive-streams) library with @apidoc[Source.fromPublisher](Source$) { scala="#fromPublisher[T](publisher:org.reactivestreams.Publisher[T]):akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#fromPublisher(org.reactivestreams.Publisher)" }.

@@@

## Example

Suppose we use a database client that supports [Reactive Streams](https://www.reactive-streams.org/),
we could create a @apidoc[Source] that queries the database for its rows. That @apidoc[Source] can then
be used for further processing, for example creating a @apidoc[Source] that contains the names of the
rows.

Because both the database driver and Akka Streams support [Reactive Streams](https://www.reactive-streams.org/),
backpressure is applied throughout the stream, preventing us from running out of memory when the database
rows are consumed slower than they are produced by the database.

Scala
:  @@snip [FromPublisher.scala](/akka-docs/src/test/scala-jdk9-only/docs/stream/operators/source/FromPublisher.scala) { #imports #example }

Java
:  @@snip [FromPublisher.java](/akka-docs/src/test/java-jdk9-only/jdocs/stream/operators/source/FromPublisher.java) { #imports #example }
