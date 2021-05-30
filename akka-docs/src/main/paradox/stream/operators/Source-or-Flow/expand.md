# expand

Like `extrapolate`, but does not have the `initial` argument, and the `Iterator` is also used in lieu of the original element, allowing for it to be rewritten and/or filtered.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

## Signature

@apidoc[Source.expand](Source) { scala="#expand[U](expander:Out=&gt;Iterator[U]):FlowOps.this.Repr[U]" java="#expand(akka.japi.function.Function)" }
@apidoc[Flow.expand](Flow) { scala="#expand[U](expander:Out=&gt;Iterator[U]):FlowOps.this.Repr[U]" java="#expand(akka.japi.function.Function)" }

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
:   @@snip [ExtrapolateAndExpand.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/ExtrapolateAndExpand.scala) { #expand }

Java
:   @@snip [ExtrapolateAndExpand.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/ExtrapolateAndExpand.java) { #expand }


## Reactive Streams semantics

@@@div { .callout }

**emits** when downstream stops backpressuring

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

