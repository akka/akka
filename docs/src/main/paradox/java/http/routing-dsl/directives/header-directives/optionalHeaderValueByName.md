<a id="optionalheadervaluebyname-java"></a>
# optionalHeaderValueByName

## Description

Optionally extracts the value of the HTTP request header with the given name.

The `optionalHeaderValueByName` directive is similar to the @ref[headerValueByName](headerValueByName.md#headervaluebyname-java) directive but always extracts
an `Optional` value instead of rejecting the request if no matching header could be found.

## Example

@@snip [HeaderDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java) { #optionalHeaderValueByName }