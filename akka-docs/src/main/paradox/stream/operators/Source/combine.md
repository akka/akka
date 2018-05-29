# combine

Combine several sources, using a given strategy such as merge or concat, into one source.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #combine }

@@@

## Description

@@@div { .callout }

**emits** when there is demand, but depending on the strategy

**completes** when all sources has completed

@@@


