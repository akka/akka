# intersperse

Intersperse stream with provided element similar to `List.mkString`.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #intersperse }

@@@

## Description

Intersperse stream with provided element similar to `List.mkString`. It can inject start and end marker elements to stream.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element or before with the *start* element if provided

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

