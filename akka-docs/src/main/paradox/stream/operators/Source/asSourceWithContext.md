# Source.asSourceWithContext

Extracts context data from the elements of a `Source` so that it can be turned into a `SourceWithContext` which can propagate that context per element along a stream.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.asSourceWithContext](Source) { scala="#asSourceWithContext[Ctx](f:Out=&gt;Ctx):akka.stream.scaladsl.SourceWithContext[Out,Ctx,Mat]" java="#asSourceWithContext(akka.japi.function.Function)" }

## Description

See @ref[Context Propagation](../../stream-context.md) for a general overview of context propagation.

Extracts context data from the elements of a @apidoc[Source] so that it can be turned into a @apidoc[SourceWithContext] which can propagate that context per element along a stream.
The function passed into `asSourceWithContext` must turn elements into contexts, one context for every element.

See also:

* @ref[Context Propagation](../../stream-context.md)
* @ref[`Flow.asFlowWithContext`](../Flow/asFlowWithContext.md) Turns a `Flow` into a `FlowWithContext` which can propagate a context per element along a stream.


## Example

Elements from this source have a correlation number, but the flow structure should focus on the text message in the elements. `asSourceWithContext` chooses the second value in the @scala[tuple]@java[pair] as the context. Another `map` operator makes the first value the stream elements in the `SourceWithContext`. 

Scala
:  @@snip [snip](/akka-docs/src/test/scala/docs/stream/operators/WithContextSpec.scala) { #asSourceWithContext }

Java
:  @@snip [snip](/akka-docs/src/test/java/jdocs/stream/operators/WithContextTest.java) { #imports #asSourceWithContext }
