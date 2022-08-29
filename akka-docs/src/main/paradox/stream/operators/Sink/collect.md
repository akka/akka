# Sink.collect

Collect all input elements using a Java @javadoc[Collector](java.util.stream.Collector).

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.collect](Sink$) { java="#collect(java.util.stream.Collector)" }

## Description

A @javadoc[Sink](akka.stream.javadsl.Sink) which materializes into a @javadoc[CompletionStage](java.util.concurrent.CompletionStage) 
which will be completed with a result of the Java @javadoc[Collector](java.util.stream.Collector) transformation and reduction operations.

## Example

Given a stream of numbers we can collect the numbers into a collection with the `seq` operator

Java
:   @@snip [SinkTest.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/SinkTest.java) { #collect-to-list }


## Reactive Streams semantics

@@@div { .callout }

**cancels** when the @javadoc[Collector](java.util.stream.Collector) throws an exception 

**backpressures** when the @javadoc[Collector](java.util.stream.Collector)'s previous accumulation is still in progress

@@@


