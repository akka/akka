# takeWhile

Pass elements downstream as long as a predicate function returns true and then complete. 

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.takeWhile](Source) { scala="#takeWhile(p:Out=&gt;Boolean,inclusive:Boolean):FlowOps.this.Repr[Out]" java="#takeWhile(akka.japi.function.Predicate)" }
@apidoc[Flow.takeWhile](Flow) { scala="#takeWhile(p:Out=&gt;Boolean,inclusive:Boolean):FlowOps.this.Repr[Out]" java="#takeWhile(akka.japi.function.Predicate)" }


## Description

Pass elements downstream as long as a predicate function returns true and then complete. 
The element for which the predicate returns false is not emitted. 

## Example

Scala
:  @@snip [TakeWhile.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/TakeWhile.scala) { #take-while }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #take-while }

## Reactive Streams semantics

@@@div { .callout }

**emits** while the predicate is true and until the first false result

**backpressures** when downstream backpressures

**completes** when predicate returned false or upstream completes

@@@
