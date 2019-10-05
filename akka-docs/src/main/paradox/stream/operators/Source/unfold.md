# Source.unfold

Stream the result of a function as long as it returns a @scala[`Some`] @java[`Optional`].

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #unfold }

@@@

## Description

Stream the result of a function as long as it returns a @scala[`Some`] @java[`Optional`]. The value inside the option
consists of a @scala[tuple] @java[pair] where the first value is a state passed back into the next call to the function allowing
to pass a state. The first invocation of the provided fold function will receive the `zero` state.

Can be used to implement many stateful sources without having to touch the more low level @ref[`GraphStage`](../../stream-customize.md) API.

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and the unfold function over the previous state returns non empty value

**completes** when the unfold function returns an empty value

@@@

