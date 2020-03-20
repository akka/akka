# logWithMarker

Log elements flowing through the stream as well as completion and erroring.

@ref[Simple operators](../index.md#simple-operators)

## Signature


Scala
:   @@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #logWithMarker }

Java
:   @@snip [FlowLogWithMarkerTest.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/FlowLogWithMarkerTest.java) { #signature }

## Description

Log elements flowing through the stream as well as completion and erroring. By default element and
completion signals are logged on debug level, and errors are logged on Error level.
This can be changed by calling @scala[`Attributes.logLevels(...)`] @java[`Attributes.createLogLevels(...)`] on the given Flow.

See also @ref:[log](log.md).

## Example

Scala
:   @@snip [SourceOrFlow.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/LogWithMarker.scala) { #logWithMarker }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #logWithMarker }

## Reactive Streams semantics 

@@@div { .callout }

**emits** when upstream emits

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@
