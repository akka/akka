# headerValueByName

@@@ div { .group-scala }

## Signature

@@signature [HeaderDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala) { #headerValueByName }

@@@

## Description

Extracts the value of the HTTP request header with the given name.

If no header with a matching name is found the request is rejected with a @unidoc[MissingHeaderRejection].

If the header is expected to be missing in some cases or to customize
handling when the header is missing use the @ref[optionalHeaderValueByName](optionalHeaderValueByName.md) directive instead.

## Example

Scala
:  @@snip [HeaderDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala) { #headerValueByName-0 }

Java
:  @@snip [HeaderDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java) { #headerValueByName }
