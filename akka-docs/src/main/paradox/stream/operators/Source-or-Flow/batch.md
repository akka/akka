# batch

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there is backpressure and a maximum number of batched elements is not yet reached.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

@@@ div { .group-scala }
## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #batch }
@@@


## Description

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there
is backpressure and a maximum number of batched elements is not yet reached. When the maximum number is reached and
downstream still backpressures batch will also backpressure.

When backpressure starts or there is no backpressure element is passed into a `seed` function to transform it
to the summary type.

Will eagerly pull elements, this behavior may result in a single pending (i.e. buffered) element which cannot be
aggregated to the batched value.

## Reactive Streams semantics

@@@div { .callout }

**emits** when downstream stops backpressuring and there is a batched element available

**backpressures** when batched elements reached the max limit of allowed batched elements & downstream backpressures

**completes** when upstream completes and a "possibly pending" element was drained

@@@

