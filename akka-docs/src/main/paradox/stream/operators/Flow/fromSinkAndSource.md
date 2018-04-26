# Flow.fromSinkAndSource

Creates a `Flow` from a `Sink` and a `Source` where the Flow's input will be sent to the `Sink` and the `Flow` 's output will come from the Source.

@ref[Flow stages composed of Sinks and Sources](../index.md#flow-stages-composed-of-sinks-and-sources)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #fromSinkAndSource }

@@@

## Description

Creates a `Flow` from a `Sink` and a `Source` where the Flow's input will be sent to the `Sink`
and the `Flow` 's output will come from the Source.

Note that termination events, like completion and cancelation is not automatically propagated through to the "other-side"
of the such-composed Flow. Use `Flow.fromSinkAndSourceCoupled` if you want to couple termination of both of the ends,
for example most useful in handling websocket connections.
