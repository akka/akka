# zipAll

Combines elements from two sources into @scala[tuples] @java[*Pair*] handling early completion of either source.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zipAll }

@@@

## Description

Combines elements from two sources into @scala[tuples] @java[*Pair*] and passes downstream.
If either source completes, a default value is combined with each value from the other source until it completes.

See also:

 * @ref:[zip](zip.md)
 * @ref:[zipWith](zipWith.md)
 * @ref:[zipWith](zipWith.md)  
 * @ref:[zipWithIndex](zipWithIndex.md)

## Example

Scala
:   @@snip [Zip.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Zip.scala) { #zipAll-simple }

Java
:   @@snip [Zip.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Zip.java) { #zipAll-simple }


## Reactive Streams semantics

@@@div { .callout }

**emits** at first emits when both inputs emit, and then as long as any input emits (coupled to the default value of the completed input)

**backpressures** both upstreams when downstream backpressures but also on an upstream that has emitted an element until the other upstream has emitted an element

**completes** when both upstream completes

@@@
