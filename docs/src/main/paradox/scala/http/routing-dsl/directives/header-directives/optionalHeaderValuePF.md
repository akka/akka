<a id="optionalheadervaluepf"></a>
# optionalHeaderValuePF

## Signature

@@signature [HeaderDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala) { #optionalHeaderValuePF }

## Description

Calls the specified partial function with the first request header the function is `isDefinedAt` and extracts the
result of calling the function.

The `optionalHeaderValuePF` directive is similar to the @ref[headerValuePF](headerValuePF.md#headervaluepf) directive but always extracts an `Option`
value instead of rejecting the request if no matching header could be found.

## Example

@@snip [HeaderDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #optionalHeaderValuePF-0 }