# apply

Stream the values of an `immutable.

@@@ div { .group-scala }
## Signature

@@signature [Source.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #apply }
@@@

## Description

Stream the values of an `immutable.Seq`.


@@@div { .callout }

**emits** the next value of the seq

**completes** when the last element of the seq has been emitted

@@@

@@@ div { .group-java }

### from

Stream the values of an `Iterable`. Make sure the `Iterable` is immutable or at least not modified after being used
as a source.

@@@

@@@

## Example

