# Flow.asFlowWithContext

Turns a Flow into a FlowWithContext which can propagate a context per element along a stream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.asFlowWithContext](Flow) { scala="#asFlowWithContext[U,CtxU,CtxOut](collapseContext:(U,CtxU)=&gt;In)(extractContext:Out=&gt;CtxOut):akka.stream.scaladsl.FlowWithContext[U,CtxU,Out,CtxOut,Mat]" java="#asFlowWithContext(akka.japi.function.Function2,akka.japi.function.Function)" }


## Description

Turns a Flow into a FlowWithContext which can propagate a context per element along a stream.
The first function passed into asFlowWithContext must turn each incoming pair of element and context value into an element of this Flow.
The second function passed into asFlowWithContext must turn each outgoing element of this Flow into an outgoing context value. 
