# headerValue

@@@ div { .group-scala }

## Signature

@@signature [HeaderDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala) { #headerValue }

@@@

## Description

Traverses the list of request headers with the specified function and extracts the first value the function returns a non empty `Optional<T>`.

The [headerValue-java]() directive is a mixture of `map` and `find` on the list of request headers. The specified function
is called once for each header until the function returns a non empty `Optional<T>`. The value of this Optional is extracted and presented to the
inner route. If the function throws an exception the request is rejected with a @unidoc[MalformedHeaderRejection]. If the
function returns `Optional.empty()` for every header the request is rejected as "NotFound".

This directive is the basis for building other request header related directives.

See also @ref[headerValuePF](headerValuePF.md) for a nicer syntactic alternative.

## Example

@@snip [HeaderDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java) { #headerValue }

### Get headerValue or return a default value

Using @ref[provide](../basic-directives/provide.md) and @ref[composing directives](../index.md#composing-directives) one can build a pattern where a headerValue is extracted if available or a default is returned. 

Scala
:  @@snip [HeaderDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #headerValue-or-default-0 }

Java
:  @@snip [HeaderDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java) { #headerValue-with-default }
