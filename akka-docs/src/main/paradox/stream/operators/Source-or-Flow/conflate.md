# conflate

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there is backpressure.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

## Signature

@apidoc[Source.conflate](Source) { scala="#conflate[O2&gt;:Out](aggregate:(O2,O2)=&gt;O2):FlowOps.this.Repr[O2]" java="#conflate(akka.japi.function.Function2)" }
@apidoc[Flow.conflate](Flow) { scala="#conflate[O2&gt;:Out](aggregate:(O2,O2)=&gt;O2):FlowOps.this.Repr[O2]" java="#conflate(akka.japi.function.Function2)" }


## Description

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as
there is backpressure. The summary value must be of the same type as the incoming elements, for example the sum or
average of incoming numbers, if aggregation should lead to a different type `conflateWithSeed` can be used:

## Example

Scala
:   @@snip [SourceOrFlow.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Conflate.scala) { #conflate }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #conflate }

If downstream is slower the elements are conflated by summing them. This means that upstream can continue producing elements while downstream is applying backpressure. For example: downstream is backpressuring while 1, 10 and 100 arrives from upstream, then backpressure stops and the conflated 111 is emitted downstream.

See @ref:[Rate transformation](../../stream-rate.md#rate-transformation) for more information and examples.

## Reactive Streams semantics 

@@@div { .callout }

**emits** when downstream stops backpressuring and there is a conflated element available

**backpressures** when the aggregate function cannot keep up with incoming elements

**completes** when upstream completes

@@@

