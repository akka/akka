# conflateWithSeed

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there is backpressure.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

## Signature

@apidoc[Source.conflateWithSeed](Source) { scala="#conflateWithSeed[S](seed:Out=&gt;S)(aggregate:(S,Out)=&gt;S):FlowOps.this.Repr[S]" java="#conflateWithSeed(akka.japi.function.Function,akka.japi.function.Function2)" }
@apidoc[Flow.conflateWithSeed](Flow) { scala="#conflateWithSeed[S](seed:Out=&gt;S)(aggregate:(S,Out)=&gt;S):FlowOps.this.Repr[S]" java="#conflateWithSeed(akka.japi.function.Function,akka.japi.function.Function2)" }


## Description

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there
is backpressure. When backpressure starts or there is no backpressure element is passed into a `seed` function to
transform it to the summary type.

## Example

Scala
:   @@snip [SourceOrFlow.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Conflate.scala) { #conflateWithSeed }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #conflateWithSeed-type #conflateWithSeed }


If downstream is slower, the "seed" function is called which is able to change the type of the to be conflated
elements if needed (it can also be an identity function, in which case this `conflateWithSeed` is equivalent to 
a plain `conflate`). Next, the conflating function is applied while there is back-pressure from the downstream,
such that the upstream can produce elements at an rate independent of the downstream.

You may want to use this operation for example to apply an average operation on the upstream elements,
while the downstream backpressures. This allows us to keep processing upstream elements, and give an average
number to the downstream once it is ready to process the next one.

See @ref:[Rate transformation](../../stream-rate.md#rate-transformation) for more information and examples.

## Reactive Streams semantics 

@@@div { .callout }

**emits** when downstream stops backpressuring and there is a conflated element available

**backpressures** when the aggregate or seed functions cannot keep up with incoming elements

**completes** when upstream completes

@@@


