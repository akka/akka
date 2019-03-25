# Source.asSourceWithContext

Turns a Source into a SourceWithContext which can propagate a context per element along a stream.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #asSourceWithContext }

@@@

## Description

Turns a Source into a SourceWithContext which can propagate a context per element along a stream.
The function passed into asSourceWithContext must turn elements into contexts, one context for every element. 
