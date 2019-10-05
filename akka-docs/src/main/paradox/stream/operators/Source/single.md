# Source.single

Stream a single object

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #single }

@@@

## Description

Stream a single object

## Reactive Streams semantics

@@@div { .callout }

**emits** the value once

**completes** when the single value has been emitted

@@@

## Examples

Scala
:  @@snip [source.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/SourceSpec.scala) { #imports #source-single }

Java
:   @@snip [source.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/SourceTest.java) { #imports #source-single }


