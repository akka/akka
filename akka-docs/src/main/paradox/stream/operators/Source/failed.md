# failed

Fail directly with a user specified exception.

@ref[Source stages](../index.md#source-stages)

@@@div { .group-scala }

## Signature

@@signature [Source.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #failed }

@@@

## Description

Fail directly with a user specified exception.


@@@div { .callout }

**emits** never

**completes** fails the stream directly with the given exception

@@@

