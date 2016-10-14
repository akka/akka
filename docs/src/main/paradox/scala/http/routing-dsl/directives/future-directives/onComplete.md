<a id="oncomplete"></a>
# onComplete

## Signature

@@signature [FutureDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FutureDirectives.scala) { #onComplete }

## Description

Evaluates its parameter of type `Future[T]`, and once the `Future` has been completed, extracts its
result as a value of type `Try[T]` and passes it to the inner route.

To handle the `Failure` case automatically and only work with the result value, use @ref[onSuccess](onSuccess.md#onsuccess).

To complete with a successful result automatically and just handle the failure result, use @ref[completeOrRecoverWith](completeOrRecoverWith.md#completeorrecoverwith), instead.

## Example

@@snip [FutureDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala) { #onComplete }