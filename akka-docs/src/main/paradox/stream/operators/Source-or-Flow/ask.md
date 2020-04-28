# ask

Use the "Ask Pattern" to send a request-reply message to the target `ref` actor (of the classic actors API).

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Signature

@apidoc[Flow.ask](Flow$) { scala="#ask%5BS](ref:akka.actor.ActorRef)(implicittimeout:akka.util.Timeout,implicittag:scala.reflect.ClassTag%5BS]):FlowOps.this.Repr%5BS]" java="#ask(akka.actor.ActorRef,java.lang.Class,akka.util.Timeout)" }

## Description

Use the @ref[Ask Pattern](../../../actors.md#ask-send-and-receive-future) to send a request-reply message to the target `ref` actor.
If any of the asks times out it will fail the stream with a @apidoc[AskTimeoutException].

The @java[`mapTo` class]@scala[`S` generic] parameter is used to cast the responses from the actor to the expected outgoing flow type.

Similar to the plain ask pattern, the target actor is allowed to reply with @apidoc[akka.actor.Status].
An @apidoc[akka.actor.Status.Failure] will cause the operator to fail with the cause carried in the `Failure` message.

Adheres to the @apidoc[ActorAttributes.SupervisionStrategy] attribute.

See also:

* @ref[ActorFlow.ask](../ActorFlow/ask.md) for the `akka.actor.typed.ActorRef[_]` variant

## Reactive Streams semantics

@@@div { .callout }

**emits** when the ask @scala[`Future`] @java[`CompletionStage`] returned by the provided function finishes for the next element in sequence


**backpressures** when the number of ask @scala[`Future` s] @java[`CompletionStage` s] reaches the configured parallelism and the downstream backpressures

**completes** when upstream completes and all ask @scala[`Future` s] @java[`CompletionStage` s] has been completed and all elements has been emitted


@@@

