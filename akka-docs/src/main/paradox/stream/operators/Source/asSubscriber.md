# asSubscriber

Integration with Reactive Streams, materializes into a `org.reactivestreams.Subscriber`.

@ref[Source operators](../index.md#source-operators)

## Signature

@@@ div { .group-scala }

@@snip[JavaFlowSupport.scala](/akka-stream/src/main/scala-jdk-9/akka/stream/scaladsl/JavaFlowSupport.scala) { #asSubscriber }

@@@

@@@ div { .group-java }

@@snip[JavaFlowSupport.java](/akka-stream/src/main/java-jdk-9/akka/stream/javadsl/JavaFlowSupport.java) { #asSubscriber }

@@@

## Description

If you want to create a @apidoc[Source] that gets its elements from another library that supports
[Reactive Streams](https://www.reactive-streams.org/), you can use `JavaFlowSupport.Source.asSubscriber`.
Each time this @apidoc[Source] is materialized, it produces a materialized value of type
@javadoc[java.util.concurrent.Subscriber](java.util.concurrent.Flow.Subscriber).
This @javadoc[Subscriber](java.util.concurrent.Flow.Subscriber) can be attached to a
[Reactive Streams](https://www.reactive-streams.org/) @javadoc[Publisher](java.util.concurrent.Flow.Publisher)
to populate it.

## Example

Suppose we use a database client that supports [Reactive Streams](https://www.reactive-streams.org/),
we could create a @apidoc[Source] that queries the database for its rows. That @apidoc[Source] can then
be used for further processing, for example creating a @apidoc[Source] that contains the names of the
rows.

Note that since the database is queried for each materialization, the `rowSource` can be safely re-used.
Because both the database driver and Akka Streams support [Reactive Streams](https://www.reactive-streams.org/),
backpressure is applied throughout the stream, preventing us from running out of memory when the database
rows are consumed slower than they are produced by the database.

Scala
:  @@snip [AsSubscriber.scala](/akka-docs/src/test/scala-jdk-9/docs/stream/operators/source/AsSubscriber.scala) { #imports #example }

Java
:  @@snip [AsSubscriber.java](/akka-docs/src/test/java-jdk-9/jdocs/stream/operators/source/AsSubscriber.java) { #imports #example }
