# Source.failed

Fail directly with a user specified exception.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.failed](Source$) { scala="#failed[T](cause:Throwable):akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#failed(java.lang.Throwable)" }


## Description

Fail directly with a user specified exception.

## Reactive Streams semantics

@@@div { .callout }

**emits** never

**completes** fails the stream directly with the given exception

@@@

