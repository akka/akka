# parameters

Extracts multiple *query* parameter values from the request.

If an unmarshaller throws an exception while extracting the value of a parameter, the request will be rejected with a `MissingQueryParameterRejection`
if the unmarshaller threw an `Unmarshaller.NoContentException` or a `MalformedQueryParamRejection` in all other cases.
(see also @ref[Rejections](../../../routing-dsl/rejections.md))

## Description

See @ref[When to use which parameter directive?](index.md#which-parameter-directive-java) to understand when to use which directive.

## Example

@@snip [ParameterDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #parameters }
