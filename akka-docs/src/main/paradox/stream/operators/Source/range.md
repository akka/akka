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

Emit each integer in a range, with an option to take bigger steps than 1. In Scala, you can use the apply method to generate a sequence of integers.


@@@div { .callout }

**emits** when there is demand, the next value

**completes** when the end of the range has been reached

@@@

## Examples

Define the range of integers.

Java
:   @@snip [Source.java]($akka$/akka-docs/src/test/java/jdocs/stream/operators/Source.java) { #imports #range }

Print out the stream of integers.

Java
:   @@snip [Source.java]($akka$/akka-docs/src/test/java/jdocs/stream/operators/Source.java) { #run-range}

