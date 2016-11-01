<a id="completeorrecoverwith-java"></a>
# completeOrRecoverWith

## Description

"Unwraps" a `CompletionStage<T>` and runs the inner route when the stage has failed
with the stage's failure exception as an extraction of type `Throwable`.
If the completion stage succeeds the request is completed using the values marshaller
(This directive therefore requires a marshaller for the completion stage value type to be
provided.)

To handle the successful case manually as well, use the @ref[onComplete](onComplete.md#oncomplete-java) directive, instead.

## Example

@@snip [FutureDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/FutureDirectivesExamplesTest.java) { #completeOrRecoverWith }