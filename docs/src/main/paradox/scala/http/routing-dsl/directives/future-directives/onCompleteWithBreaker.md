<a id="oncompletewithbreaker"></a>
# onCompleteWithBreaker

## Signature

@@signature [FutureDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FutureDirectives.scala) { #onCompleteWithBreaker }

## Description

Evaluates its parameter of type `Future[T]` protecting it with the specified `CircuitBreaker`.
Refer to @extref[Circuit Breaker](akka-docs:common/circuitbreaker.html) for a detailed description of this pattern.

If the `CircuitBreaker` is open, the request is rejected with a `CircuitBreakerOpenRejection`.
Note that in this case the request's entity databytes stream is cancelled, and the connection is closed
as a consequence.

Otherwise, the same behaviour provided by @ref[onComplete](onComplete.md#oncomplete) is to be expected.

## Example

@@snip [FutureDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala) { #onCompleteWithBreaker }