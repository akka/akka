# from

Stream the values of an `Iterable`.

@ref[Source operators](../index.md#source-operators)


## Signature

@@signature [Source.scala]($akka$/akka-stream/src/main/scala/akka/stream/javadsl/Source.scala) { #from }


## Description

Stream the values of an `Iterable`. Make sure the `Iterable` is immutable or at least not modified after being used
as a source. Otherwise the stream may fail with `ConcurrentModificationException` or other more subtle errors may occur.

@@@div { .callout }

**emits** the next value of the seq

**completes** when the last element of the seq has been emitted

@@@


## Examples

Java
:  @@snip [from.java]($akka$/akka-stream-tests/src/test/java/akka/stream/javadsl/SourceTest.java) { #source-from-example }
