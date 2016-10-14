<a id="optionalheadervalue"></a>
# optionalHeaderValue

## Signature

@@signature [HeaderDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala) { #optionalHeaderValue }

## Description

Traverses the list of request headers with the specified function and extracts the first value the function returns as
`Some(value)`.

The `optionalHeaderValue` directive is similar to the @ref[headerValue](headerValue.md#headervalue) directive but always extracts an `Option`
value instead of rejecting the request if no matching header could be found.

## Example

@@snip [HeaderDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #optionalHeaderValue-0 }