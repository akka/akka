# Source.asSourceWithContext

Turns a Source into a SourceWithContext which can propagate a context per element along a stream.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.asSourceWithContext](Source) { scala="#asSourceWithContext[Ctx](f:Out=&gt;Ctx):akka.stream.scaladsl.SourceWithContext[Out,Ctx,Mat]" java="#asSourceWithContext(akka.japi.function.Function)" }


## Description

Turns a Source into a SourceWithContext which can propagate a context per element along a stream.
The function passed into asSourceWithContext must turn elements into contexts, one context for every element. 
