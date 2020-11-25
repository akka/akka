# Flow.asFlowWithContext

Extracts context data from the elements of a `Flow` so that it can be turned into a `FlowWithContext` which can propagate that context per element along a stream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.asFlowWithContext](Flow) { scala="#asFlowWithContext[U,CtxU,CtxOut](collapseContext:(U,CtxU)=&gt;In)(extractContext:Out=&gt;CtxOut):akka.stream.scaladsl.FlowWithContext[U,CtxU,Out,CtxOut,Mat]" java="#asFlowWithContext(akka.japi.function.Function2,akka.japi.function.Function)" }

## Description

See @ref[Context Propagation](../../stream-context.md) for a general overview of context propagation.

Extracts context data from the elements of a @apidoc[Flow] so that it can be turned into a @apidoc[FlowWithContext] which can propagate that context per element along a stream.
The first function passed into `asFlowWithContext` must turn each incoming pair of element and context value into an element of this @apidoc[Flow].
The second function passed into `asFlowWithContext` must turn each outgoing element of this @apidoc[Flow] into an outgoing context value.

See also:

* @ref[Context Propagation](../../stream-context.md)
* @ref[`Source.asSourceWithContext`](../Source/asSourceWithContext.md) Turns a `Source` into a `SourceWithContext` which can propagate a context per element along a stream.

## Example

Elements from this flow have a correlation number, but the flow structure should focus on the text message in the elements. The first converter in `asFlowWithContext` applies to the end of the "with context" flow to turn it into a regular flow again. The second converter function chooses the second value in the @scala[tuple]@java[pair] as the context. Another `map` operator makes the first value the stream elements in the `FlowWithContext`. 

Scala
:  @@snip [snip](/akka-docs/src/test/scala/docs/stream/operators/WithContextSpec.scala) { #asFlowWithContext }

Java
:  @@snip [snip](/akka-docs/src/test/java/jdocs/stream/operators/WithContextTest.java) { #imports #asFlowWithContext }
