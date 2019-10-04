# orElse

If the primary source completes without emitting any elements, the elements from the secondary source are emitted.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #orElse }

@@@

## Description

If the primary source completes without emitting any elements, the elements from the secondary source
are emitted. If the primary source emits any elements the secondary source is cancelled.

Note that both sources are materialized directly and the secondary source is backpressured until it becomes
the source of elements or is cancelled.

Signal errors downstream, regardless which of the two sources emitted the error.

## Reactive Streams semantics

@@@div { .callout }

**emits** when an element is available from first stream or first stream closed without emitting any elements and an element
is available from the second stream

**backpressures** when downstream backpressures

**completes** the primary stream completes after emitting at least one element, when the primary stream completes
without emitting and the secondary stream already has completed or when the secondary stream completes

@@@


## Example
Scala
:   @@snip [FlowOrElseSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowOrElseSpec.scala) { #or-else }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #or-else }