# apply

Stream the values of an `immutable.Seq`.

@ref[Source operators](../index.md#source-operators)

@@@ div { .group-scala }
## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #apply }
@@@

## Description

Stream the values of an `immutable.Seq`.

@@@div { .callout }

**emits** the next value of the seq

**completes** when the last element of the seq has been emitted

@@@

