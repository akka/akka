# @scala[apply]@java[from]

Stream the values of an @scala[`immutable.Seq`]@java[`Iterable`].

@ref[Source operators](../index.md#source-operators)


@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #apply }

@@@

## Description

Stream the values of an @scala[`immutable.Seq`]@java[`Iterable`]. @java[Make sure the `Iterable` is immutable or at least not modified after being used
as a source. Otherwise the stream may fail with `ConcurrentModificationException` or other more subtle errors may occur.]

## Reactive Streams semantics

@@@div { .callout }

**emits** the next value of the seq

**completes** when the last element of the seq has been emitted

@@@


## Examples

Java
:  @@snip [from.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceDocExamples.java) { #imports #source-from-example }
