# Source.range

Emit each integer in a range, with an option to take bigger steps than 1.

@ref[Source operators](../index.md#source-operators)

## Dependency

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-stream_$scala.binary_version$"
  version="$akka.version$"
}


## Description

Emit each integer in a range, with an option to take bigger steps than 1. @scala[In Scala, use the `apply` method to generate a sequence of integers.]

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand, the next value

**completes** when the end of the range has been reached

@@@

## Examples

Define the range of integers.

Java
:   @@snip [SourceDocExamples.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceDocExamples.java) { #range-imports #range }

Print out the stream of integers.

Java
:   @@snip [SourceDocExamples.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceDocExamples.java) { #run-range}
