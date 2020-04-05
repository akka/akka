# fromJavaStream

Stream the values from a Java 8 `Stream`, requesting the next value when there is demand.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #fromJavaStream }

@@@

## Description

Stream the values from a Java 8 `Stream`, requesting the next value when there is demand. The iterator will be created anew
for each materialization, which is the reason the @scala[`method`] @java[`factory`] takes a @scala[`function`] @java[`Creator`] rather than an `Stream` directly.

 You can use [[Source.async]] to create asynchronous boundaries between synchronous java stream and the rest of flow.
## Example
 
Scala
:   @@snip [From.scala](/akka-docs/src/test/scala/docs/stream/operators/source/From.scala) { #from-javaStream }

Java
:   @@snip [From.java](/akka-docs/src/test/java/jdocs/stream/operators/source/From.java) { #from-javaStream }


## Reactive Streams semantics

@@@div { .callout }

**emits** the next value returned from the iterator

**completes** when the iterator reaches its end

@@@

