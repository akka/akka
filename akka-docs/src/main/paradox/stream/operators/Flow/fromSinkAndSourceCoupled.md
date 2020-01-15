# Flow.fromSinkAndSourceCoupled

Allows coupling termination (cancellation, completion, erroring) of Sinks and Sources while creating a Flow between them.

@ref[Flow operators composed of Sinks and Sources](../index.md#flow-operators-composed-of-sinks-and-sources)

## Signature

@apidoc[Flow.fromSinkAndSourceCoupled](Flow$) { scala="#fromSinkAndSourceCoupled[I,O](sink:akka.stream.Graph[akka.stream.SinkShape[I],_],source:akka.stream.Graph[akka.stream.SourceShape[O],_]):akka.stream.scaladsl.Flow[I,O,akka.NotUsed]" java="#fromSinkAndSourceCoupled(akka.stream.Graph,akka.stream.Graph)" }

## Description

See @ref[Flow.fromSinkAndSource](fromSinkAndSource.md) for docs on the general workings and examples.

This operator only adds coupled termination to what `fromSinkAndSource` does: If the emitted `Flow` gets a cancellation, the `Source` is cancelled,
however the Sink will also be completed. The table below illustrates the effects in detail:

| Returned Flow                                   | Sink (in)                   | Source (out)                    |
|-------------------------------------------------|-----------------------------|---------------------------------|
| cause: upstream (sink-side) receives completion | effect: receives completion | effect: receives cancel         |
| cause: upstream (sink-side) receives error      | effect: receives error      | effect: receives cancel         |
| cause: downstream (source-side) receives cancel | effect: completes           | effect: receives cancel         |
| effect: cancels upstream, completes downstream  | effect: completes           | cause: signals complete         |
| effect: cancels upstream, errors downstream     | effect: receives error      | cause: signals error or throws  |
| effect: cancels upstream, completes downstream  | cause: cancels              | effect: receives cancel         |

The order in which the *in* and *out* sides receive their respective completion signals is not defined, do not rely on its ordering.

