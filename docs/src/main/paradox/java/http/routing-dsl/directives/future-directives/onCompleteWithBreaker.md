<a id="oncompletewithbreaker-java"></a>
# onCompleteWithBreaker

## Description

Evaluates its parameter of type `CompletionStage<T>` protecting it with the specified `CircuitBreaker`.
Refer to @extref[Circuit Breaker](akka-docs:common/circuitbreaker.html) for a detailed description of this pattern.

If the `CircuitBreaker` is open, the request is rejected with a `CircuitBreakerOpenRejection`.
Note that in this case the request's entity databytes stream is cancelled, and the connection is closed
as a consequence.

Otherwise, the same behaviour provided by @ref[onComplete](onComplete.md#oncomplete-java) is to be expected.

## Example

@@snip [FutureDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/FutureDirectivesExamplesTest.java) { #onCompleteWithBreaker }