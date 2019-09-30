# Source.unfoldResource

Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #unfoldResource }

@@@

## Description

Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.

The functions are by default called on the blocking io dispatcher to avoid interfering with other stream operations. 
See @ref:[Blocking Needs Careful Management](../../../typed/dispatchers.md#blocking-needs-careful-management) for an explanation on why this is important

@@@div { .callout }

**emits** when there is demand and the `read` function returns value

**completes** when read function returns @scala[`None`]@java[an empty `Optional`]

@@@

## Examples

Imagine we have a blocking database API which may potentially block both when we initially perform a query and 
on retrieving each result from the query. It also gives us an iterator like way to determine if we have reached
the end of the result and a close method that must be called to free resources:

Scala
:   @@snip [UnfoldResource.scala](/akka-docs/src/test/scala/docs/stream/operators/source/UnfoldResource.scala) { #unfoldResource-blocking-api }

Java
:   @@snip [UnfoldResource.java](/akka-docs/src/test/java/jdocs/stream/operators/source/UnfoldResource.java) { #unfoldResource-blocking-api }


`Source.unfoldResource` allows us to safely interact with this API by providing three functions: 

1. open the resource
1. fetch the next element or signal that we reached the end of the stream
1. close the resource, invoked on end of stream or if the stream fails

Let's see how we use the API above safely through `unfoldResource`:

Scala
:   @@snip [UnfoldResource.scala](/akka-docs/src/test/scala/docs/stream/operators/source/UnfoldResource.scala) { #unfoldResource }

Java
:   @@snip [UnfoldResource.java](/akka-docs/src/test/java/jdocs/stream/operators/source/UnfoldResource.java) { #unfoldResource }
