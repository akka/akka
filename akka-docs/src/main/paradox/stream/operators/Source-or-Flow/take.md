# take

Pass `n` incoming elements downstream and then complete

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.take](Source) { scala="#take(n:Long):FlowOps.this.Repr[Out]" java="#take(long)" }
@apidoc[Flow.take](Flow) { scala="#take(n:Long):FlowOps.this.Repr[Out]" java="#take(long)" }


## Description

Pass `n` incoming elements downstream and then complete

## Example

Scala
:  @@snip [Take.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Take.scala) { #take }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #take }


## Reactive Streams semantics

@@@div { .callout }

**emits** while the specified number of elements to take has not yet been reached

**backpressures** when downstream backpressures

**completes** when the defined number of elements has been taken or upstream completes

@@@

