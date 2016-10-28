<a id="optionalheadervaluepf-java"></a>
# optionalHeaderValuePF

## Description

Calls the specified partial function with the first request header the function is `isDefinedAt` and extracts the
result of calling the function.

The `optionalHeaderValuePF` directive is similar to the @ref[headerValuePF](headerValuePF.md#headervaluepf-java) directive but always extracts an `Optional`
value instead of rejecting the request if no matching header could be found.

## Example

@@snip [HeaderDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java) { #optionalHeaderValuePF }