<a id="headervaluebyname-java"></a>
# headerValueByName

## Description

Extracts the value of the HTTP request header with the given name.

If no header with a matching name is found the request is rejected with a `MissingHeaderRejection`.

If the header is expected to be missing in some cases or to customize
handling when the header is missing use the @ref[optionalHeaderValueByName](optionalHeaderValueByName.md#optionalheadervaluebyname-java) directive instead.

## Example

@@snip [HeaderDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java) { #headerValueByName }