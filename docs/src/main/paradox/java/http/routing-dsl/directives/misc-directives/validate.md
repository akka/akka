<a id="validate-java"></a>
# validate

Allows validating a precondition before handling a route.

## Description

Checks an arbitrary condition and passes control to the inner route if it returns `true`.
Otherwise, rejects the request with a `ValidationRejection` containing the given error message.

## Example

@@snip [MiscDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #validate-example }