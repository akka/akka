<a id="headervaluebyname"></a>
# headerValueByName

## Signature

@@signature [HeaderDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala) { #headerValueByName }

## Description

Extracts the value of the HTTP request header with the given name.

The name can be given as a `String` or as a `Symbol`. If no header with a matching name is found the request
is rejected with a `MissingHeaderRejection`.

If the header is expected to be missing in some cases or to customize
handling when the header is missing use the @ref[optionalHeaderValueByName](optionalHeaderValueByName.md#optionalheadervaluebyname) directive instead.

## Example

@@snip [HeaderDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #headerValueByName-0 }