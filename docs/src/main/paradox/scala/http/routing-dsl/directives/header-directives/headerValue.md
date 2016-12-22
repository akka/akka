<a id="headervalue"></a>
# headerValue

## Signature

@@signature [HeaderDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala) { #headerValue }

## Description

Traverses the list of request headers with the specified function and extracts the first value the function returns as
`Some(value)`.

The [headerValue](#headervalue) directive is a mixture of `map` and `find` on the list of request headers. The specified function
is called once for each header until the function returns `Some(value)`. This value is extracted and presented to the
inner route. If the function throws an exception the request is rejected with a `MalformedHeaderRejection`. If the
function returns `None` for every header the request is rejected as "NotFound".

This directive is the basis for building other request header related directives.

See also @ref[headerValuePF](headerValuePF.md#headervaluepf) for a nicer syntactic alternative.

## Example

@@snip [HeaderDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #headerValue-0 }

### Get headerValue or return a default

Using @ref[provide](../basic-directives/provide.md#provide) and @ref[composing directives](../index.md#composing-directives) one can build a pattern where a headerValue is extracted if available or a default is returned. 

@@snip [HeaderDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #headerValue-or-default-0 }
