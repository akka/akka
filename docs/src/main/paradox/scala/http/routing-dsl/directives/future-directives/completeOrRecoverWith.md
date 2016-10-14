<a id="completeorrecoverwith"></a>
# completeOrRecoverWith

## Signature

@@signature [FutureDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FutureDirectives.scala) { #completeOrRecoverWith }

## Description

If the `Future[T]` succeeds the request is completed using the value's marshaller (this directive therefore
requires a marshaller for the future's parameter type to be implicitly available). The execution of the inner
route passed to this directive is only executed if the given future completed with a failure,
exposing the reason of failure as an extraction of type `Throwable`.

To handle the successful case manually as well, use the @ref[onComplete](onComplete.md#oncomplete) directive, instead.

## Example

@@snip [FutureDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala) { #completeOrRecoverWith }