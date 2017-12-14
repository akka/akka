# overrideMethodWithParameter

Changes the HTTP method of the request to the value of the specified query string parameter. 

## Description

If the query string parameter is not specified this directive has no effect. If the query string is specified as something that is not
a HTTP method, then this directive completes the request with a *501 Not Implemented* response.

This directive is useful for:

 * Use in combination with JSONP (JSONP only supports GET)
 * Supporting older browsers that lack support for certain HTTP methods. E.g. IE8 does not support PATCH

## Example

Scala
:  @@snip [MethodDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/MethodDirectivesExamplesSpec.scala) { #overrideMethodWithParameter-0 }

Java
:  @@snip [MethodDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MethodDirectivesExamplesTest.java) { #overrideMethodWithParameter }
