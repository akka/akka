# preMaterialize

Materializes this Graph, immediately returning (1) its materialized value, and (2) a new pre-materialized Graph.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.preMaterialize](Source) { scala="#preMaterialize()(implicitmaterializer:akka.stream.Materializer):(Mat,akka.stream.scaladsl.Source[Out,akka.NotUsed])" java="#preMaterialize(akka.actor.ClassicActorSystemProvider)" java="#preMaterialize(akka.stream.Materializer)" }
@apidoc[Flow.preMaterialize](Flow) { scala="#preMaterialize()(implicitmaterializer:akka.stream.Materializer):(Mat,akka.stream.scaladsl.Flow[Int,Out,akka.NotUsed])" java="#preMaterialize(akka.actor.ClassicActorSystemProvider)" java="#preMaterialize(akka.stream.Materializer)" }


## Description

Materializes this Graph, immediately returning (1) its materialized value, and (2) a new pre-materialized Graph.

Note that `preMaterialize` is implemented through a reactive streams `Publisher` for a `Source` or a `Publisher` 
and `Subscriber` pair for a `Flow` which means that a buffer is introduced and that errors are not propagated upstream 
but are turned into cancellations without error details.
