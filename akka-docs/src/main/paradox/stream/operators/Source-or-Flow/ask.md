# Flow.ask

Use the `ask` pattern to send a request-reply message to the target `ref` actor.

@ref[Asynchronous processing stages](../index.md#asynchronous-processing-stages)

@@@ div { .group-scala }
## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #ask }
@@@

## Description

Use the `ask` pattern to send a request-reply message to the target `ref` actor.
If any of the asks times out it will fail the stream with a [[akka.pattern.AskTimeoutException]].

The `mapTo` class parameter is used to cast the incoming responses to the expected response type.

Similar to the plain ask pattern, the target actor is allowed to reply with `akka.util.Status`.
An `akka.util.Status#Failure` will cause the stage to fail with the cause carried in the `Failure` message.

Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.


@@@div { .callout }

**emits** when the ask @scala[`Future`] @java[`CompletionStage`] returned by the provided function finishes for the next element in sequence


**backpressures** when the number of ask @scala[`Future` s] @java[`CompletionStage` s] reaches the configured parallelism and the downstream backpressures

**completes** when upstream completes and all ask @scala[`Future` s] @java[`CompletionStage` s] has been completed and all elements has been emitted


@@@

