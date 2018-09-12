# log

Log elements flowing through the stream as well as completion and erroring.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #log }

@@@

## Description

Log elements flowing through the stream as well as completion and erroring. By default element and
completion signals are logged on debug level, and errors are logged on Error level.
This can be changed by calling @scala[`Attributes.logLevels(...)`] @java[`Attributes.createLogLevels(...)`] on the given Flow.


@@@div { .callout }

**emits** when upstream emits

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Example

Scala
:   @@snip [SourceOrFlow.scala]($code$/scala/docs/stream/operators/SourceOrFlow.scala) { #log }

Java
:   @@snip [SourceOrFlow.java]($code$/java/jdocs/stream/operators/SourceOrFlow.java) { #log }
