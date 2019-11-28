# scan

Emit its current value, which starts at `zero`, and then apply the current and next value to the given function, emitting the next current value.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #scan }

@@@

## Description

Emit its current value, which starts at `zero`, and then apply the current and next value to the given function,
emitting the next current value. This means that `scan` emits one element downstream before, and upstream elements
will not be requested until, the second element is required from downstream.

@@@ warning

Note that the `zero` value must be immutable, because otherwise
the same mutable instance would be shared across different threads
when running the stream more than once.

@@@

## Examples

Below example demonstrates how `scan` is similar to `fold`, but it keeps value from every iteration.

Scala
:  @@snip [Scan.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Scan.scala) { #scan }

Java
:  @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #scan }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the function scanning the element returns a new element

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@
