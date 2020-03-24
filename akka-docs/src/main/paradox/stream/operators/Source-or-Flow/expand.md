# expand

Like `extrapolate`, but does not have the `initial` argument, and the `Iterator` is also used in lieu of the original element, allowing for it to be rewritten and/or filtered.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #expand }

@@@

## Description

Like `extrapolate`, but does not have the `initial` argument, and the `Iterator` is also used in lieu of the original 
element, allowing for it to be rewritten and/or filtered.

See @ref:[Understanding extrapolate and expand](../../stream-rate.md#understanding-extrapolate-and-expand) for more information
and examples.

## Example

Imagine a streaming client decoding a video. It is possible the network bandwidth is a bit 
unreliable. It's fine, as long as the audio remains fluent, it doesn't matter if we can't decode 
a frame or two (or more). But we also want to watermark every decoded frame with the name of 
our colleague. `expand` provides access to the element flowing through the stream
and let's us create extra frames in case the producer slows down:

Scala
:   @@snip [ExpandScala.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/ExpandScala.scala) { #expand }

Java
:   @@snip [ExpandJava.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/ExpandJava.java) { #expand }


## Reactive Streams semantics

@@@div { .callout }

**emits** when downstream stops backpressuring

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

