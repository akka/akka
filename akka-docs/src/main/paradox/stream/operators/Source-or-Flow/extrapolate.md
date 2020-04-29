# extrapolate

Allow for a faster downstream by expanding the last emitted element to an `Iterator`.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

## Signature

@apidoc[Source.extrapolate](Source) { scala="#extrapolate[U&gt;:Out](extrapolator:U=&gt;Iterator[U],initial:Option[U]):FlowOps.this.Repr[U]" java="#extrapolate(akka.japi.function.Function,java.lang.Object)" }
@apidoc[Flow.extrapolate](Flow) { scala="#extrapolate[U&gt;:Out](extrapolator:U=&gt;Iterator[U],initial:Option[U]):FlowOps.this.Repr[U]" java="#extrapolate(akka.japi.function.Function,java.lang.Object)" }

## Description

Allow for a faster downstream by expanding the last emitted element to an `Iterator`. For example, an
`Iterator.continually(element)` will cause `extrapolate` to keep repeating the last emitted element. 

All original elements are always emitted unchanged - the `Iterator` is only used whenever there is downstream
 demand before upstream emits a new element.

Includes an optional `initial` argument to prevent blocking the entire stream when there are multiple producers.

See @ref:[Understanding extrapolate and expand](../../stream-rate.md#understanding-extrapolate-and-expand) for more information
and examples.

## Example

Imagine a videoconference client decoding a video feed from a colleague working remotely. It is possible 
the network bandwidth is a bit unreliable. It's fine, as long as the audio remains fluent, it doesn't matter
if we can't decode a frame or two (or more). When a frame is dropped, though, we want the UI to show the last 
frame decoded:

Scala
:   @@snip [ExtrapolateAndExpand.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/ExtrapolateAndExpand.scala) { #extrapolate }

Java
:   @@snip [ExtrapolateAndExpand.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/ExtrapolateAndExpand.java) { #extrapolate }

## Reactive Streams semantics

@@@div { .callout }

**emits** when downstream stops backpressuring

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@
