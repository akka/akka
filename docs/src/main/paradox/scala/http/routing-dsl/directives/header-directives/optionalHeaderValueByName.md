<a id="optionalheadervaluebyname"></a>
# optionalHeaderValueByName

## Signature

@@signature [HeaderDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala) { #optionalHeaderValueByName }

## Description

Optionally extracts the value of the HTTP request header with the given name.

The `optionalHeaderValueByName` directive is similar to the @ref[headerValueByName](headerValueByName.md#headervaluebyname) directive but always extracts
an `Option` value instead of rejecting the request if no matching header could be found.

## Example

@@snip [HeaderDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #optionalHeaderValueByName-0 }