# Flow.ask

Use the `ask` pattern to send a request-reply message to the target `ref` actor.

@ref[Asynchronous operators](../index.md#asynchronous-operators)

@@@ div { .group-scala }
## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #ask }
@@@

## Description

Use the `ask` pattern to send a request-reply message to the target `ref` actor.
If any of the asks times out it will fail the stream with a @apidoc[AskTimeoutException].

The @java[`mapTo` class]@scala[`S` generic] parameter is used to cast the responses from the actor to the expected outgoing flow type.

Similar to the plain ask pattern, the target actor is allowed to reply with `akka.util.Status`.
An `akka.util.Status#Failure` will cause the operator to fail with the cause carried in the `Failure` message.

Adheres to the @scala[@scaladoc[`ActorAttributes.SupervisionStrategy`](akka.stream.ActorAttributes$$SupervisionStrategy)]
@java[`ActorAttributes.SupervisionStrategy`] attribute.


@@@div { .callout }

**emits** when the ask @scala[`Future`] @java[`CompletionStage`] returned by the provided function finishes for the next element in sequence


**backpressures** when the number of ask @scala[`Future` s] @java[`CompletionStage` s] reaches the configured parallelism and the downstream backpressures

**completes** when upstream completes and all ask @scala[`Future` s] @java[`CompletionStage` s] has been completed and all elements has been emitted


@@@

