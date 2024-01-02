# zipLatest

Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream, picking always the latest element of each.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.zipLatest](Source) { scala="#zipLatest[U](that:akka.stream.Graph[akka.stream.SourceShape[U],_]):FlowOps.this.Repr[(Out,U)]" java="#zipLatest(akka.stream.Graph)" }
@apidoc[Flow.zipLatest](Flow) { scala="#zipLatest[U](that:akka.stream.Graph[akka.stream.SourceShape[U],_]):FlowOps.this.Repr[(Out,U)]" java="#zipLatest(akka.stream.Graph)" }


## Description

Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream, picking always the latest element of each.

No element is emitted until at least one element from each Source becomes available.

## Example

Scala
:   @@snip [StreamConvertersToJava.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Zip.scala) { #zipLatest-example }

Java
:   @@snip [StreamConvertersToJava.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Zip.java) { #zipLatest-example }

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the inputs have at least an element available, and then each time an element becomes
          available on either of the inputs

**backpressures** when downstream backpressures

**completes** when any upstream completes

**cancels** when downstream cancels

@@@

