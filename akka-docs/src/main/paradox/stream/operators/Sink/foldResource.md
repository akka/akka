# foldResource

Handle elements with the help of a resource that can be opened, handle each element (in a blocking way) and closed.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.foldResource](Sink$) { scala="
#foldResource%5BR%2C%20T%5D%28create%3A%20%28%29%20%3D%3E%20R%29%28f%3A%20%28R%2C%20T%29%20%3D%3E%20Unit%2C%20close%3A%20R%20%3D%3E%20Unit%29%3A%20Sink%5BT%2C%20Future%5BDone%5D%5D"
java="#foldResource(akka.japi.function.Creator,akka.japi.function.Procedure2,akka.japi.function.Procedure)" }

1. `create`: Open or Create the resource.
2. `f`: Handle each element inputs with the help of resource.
3. `close`: Close the resource, invoked on end of stream or if the stream fails.

## Description

Handle each stream element with the help of a resource.
The functions are by default called on Akka's dispatcher for blocking IO to avoid interfering with other stream
operations.
See @ref:[Blocking Needs Careful Management](../../../typed/dispatchers.md#blocking-needs-careful-management) for an
explanation on why this is important.
The resource creation function is invoked once when the stream is materialized and the returned resource is passed to
the function `f` for handling every stream element.

The `close` function is called when upstream or current stage completes normally or exceptionally, and will be called
only once.

- upstream completes or fails
- current stage completes or fails
- shutdowns abruptly

You can do some clean-up here.

Early completion can be done with combination of the @apidoc[Flow.takeWhile](Flow) operator.

See also :

- @ref:[unfoldResource](../Source/unfoldResource.md), @ref:[unfoldResourceAsync](../Source/unfoldResourceAsync.md) Source operators
- @ref:[mapWithResource](../Source-or-Flow/mapWithResource.md) Source & Flow operators
- @ref:[asInputStream](../StreamConverters/asInputStream.md) Create a sink which materializes into an `InputStream`  
- @ref:[fromOutputStream](../StreamConverters/fromOutputStream.md) Create a sink that wraps an `OutputStream`

You can configure the default dispatcher for this Sink by changing the `akka.stream.materializer.blocking-io-dispatcher`
or set it for a given Sink by using ActorAttributes.

## Examples

Imagine we have a database API which may potentially block when we perform an insert,
and the database connection can be reused for each insert operation.

Scala
:   @@snip [FoldResource.scala](/akka-docs/src/test/scala/docs/stream/operators/sink/FoldResource.scala) {
#foldResource-blocking-api }

Java
:   @@snip [FoldResource.java](/akka-docs/src/test/java/jdocs/stream/operators/sink/FoldResource.java) {
#foldResource-blocking-api }

Let's see how we make use of the API above safely through `foldResource`:

Scala
:   @@snip [FoldResource.scala](/akka-docs/src/test/scala/docs/stream/operators/sink/FoldResource.scala) { #foldResource
}

Java
:   @@snip [FoldResource.java](/akka-docs/src/test/java/jdocs/stream/operators/sink/FoldResource.java) { #foldResource }

In this example we accept inserts from a Source and execute the insert operation in the Sink, once done the connection
is closed.

## Reactive Streams semantics

@@@div { .callout }

**backpressures** when the previous function invocation has not yet completed

**completes** upstream completes

**cancels** never

@@@
