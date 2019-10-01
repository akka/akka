# combine

Combine several sources, using a given strategy such as merge or concat, into one source.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #combine }

@@@

## Description

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand, but depending on the strategy

**completes** when all sources has completed

@@@


## Examples


Scala
:  @@snip [combine.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/SourceSpec.scala) { #imports #combine }



