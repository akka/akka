# Sink.preMaterialize

Materializes this Sink, immediately returning (1) its materialized value, and (2) a new Sink that can be consume elements 'into' the pre-materialized one.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.preMaterialize](Sink) { scala="#preMaterialize()(implicitmaterializer:akka.stream.Materializer):(Mat,akka.stream.scaladsl.Sink[In,akka.NotUsed])" java="#preMaterialize(akka.actor.ClassicActorSystemProvider)" java="#preMaterialize(akka.stream.Materializer)" }


## Description

Materializes this Sink, immediately returning (1) its materialized value, and (2) a new Sink that can be consume elements 'into' the pre-materialized one. Useful for when you need a materialized value of a Sink when handing it out to someone to materialize it for you.

Note that `preMaterialize` is implemented through a reactive streams `Subscriber` which means that a buffer is introduced
and that errors are not propagated upstream but are turned into cancellations without error details.
