# foreach

Invoke a given procedure for each element received.

@ref[Sink stages](../index.md#sink-stages)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #forEach }

@@@

## Description

Invoke a given procedure for each element received. Note that it is not safe to mutate shared state from the procedure.

The sink materializes into a  @scala[`Future[Option[Done]]`] @java[`CompletionStage<Optional<Done>`] which completes when the
stream completes, or fails if the stream fails.

Note that it is not safe to mutate state from the procedure.


@@@div { .callout }

**cancels** never

**backpressures** when the previous procedure invocation has not yet completed

@@@


