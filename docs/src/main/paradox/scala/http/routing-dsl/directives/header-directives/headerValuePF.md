<a id="headervaluepf"></a>
# headerValuePF

## Signature

@@signature [HeaderDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala) { #headerValuePF }

## Description

Calls the specified partial function with the first request header the function is `isDefinedAt` and extracts the
result of calling the function.

The `headerValuePF` directive is an alternative syntax version of @ref[headerValue](headerValue.md#headervalue).

If the function throws an exception the request is rejected with a `MalformedHeaderRejection`.

If the function is not defined for any header the request is rejected as "NotFound".

## Example

@@snip [HeaderDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #headerValuePF-0 }