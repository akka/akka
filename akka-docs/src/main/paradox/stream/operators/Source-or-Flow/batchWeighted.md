# batchWeighted

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there is backpressure and a maximum weight batched elements is not yet reached.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

## Signature

@apidoc[Source.batchWeighted](Source) { scala="#batchWeighted[S](max:Long,costFn:Out=&gt;Long,seed:Out=&gt;S)(aggregate:(S,Out)=&gt;S):FlowOps.this.Repr[S]" java="#batchWeighted(long,akka.japi.function.Function,akka.japi.function.Function,akka.japi.function.Function2)" }
@apidoc[Flow.batchWeighted](Flow) { scala="#batchWeighted[S](max:Long,costFn:Out=&gt;Long,seed:Out=&gt;S)(aggregate:(S,Out)=&gt;S):FlowOps.this.Repr[S]" java="#batchWeighted(long,akka.japi.function.Function,akka.japi.function.Function,akka.japi.function.Function2)" }



## Description

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there
is backpressure and a maximum weight batched elements is not yet reached. The weight of each element is determined by
applying `costFn`. When the maximum total weight is reached and downstream still backpressures batch will also
backpressure.

Will eagerly pull elements, this behavior may result in a single pending (i.e. buffered) element which cannot be
aggregated to the batched value.

## Reactive Streams semantics

@@@div { .callout }

**emits** downstream stops backpressuring and there is a batched element available

**backpressures** batched elements reached the max weight limit of allowed batched elements & downstream backpressures

**completes** upstream completes and a "possibly pending" element was drained

@@@

