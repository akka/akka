# Broadcast

Emit each incoming element each of `n` outputs.

@ref[Fan-out operators](index.md#fan-out-operators)

## Signature

@apidoc[akka.stream.*.Broadcast]

## Description

Emit each incoming element each of `n` outputs.

## Example

Here is an example that is using `Broadcast` to aggregate different values from a `Source` of integers.

Scala
:   @@snip [BroadcastDocExample.scala](/akka-docs/src/test/scala/docs/stream/operators/BroadcastDocExample.scala) { #broadcast }

Java
:   @@snip [BroadcastDocExample.java](/akka-docs/src/test/java/jdocs/stream/operators/BroadcastDocExample.java) { #import #broadcast }

Note that asynchronous boundary for the output streams must be added explicitly if it's desired to run them in parallel.

Scala
:   @@snip [BroadcastDocExample.scala](/akka-docs/src/test/scala/docs/stream/operators/BroadcastDocExample.scala) { #broadcast-async }

Java
:   @@snip [BroadcastDocExample.java](/akka-docs/src/test/java/jdocs/stream/operators/BroadcastDocExample.java) { #broadcast-async }

 

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the outputs stops backpressuring and there is an input element available

**backpressures** when any of the outputs backpressures

**completes** when upstream completes

**cancels** depends on the `eagerCancel` flag. If it is true, when any downstream cancels, if false, when all downstreams cancel.

@@@


