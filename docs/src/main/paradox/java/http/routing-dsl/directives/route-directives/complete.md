# complete

## Description

Completes the request using the given argument(s).

`complete` uses the given arguments to construct a @unidoc[Route] which simply calls `complete` on the @unidoc[RequestContext]
with the respective @unidoc[HttpResponse] instance.
Completing the request will send the response "back up" the route structure where all the logic runs that wrapping
directives have potentially chained into the @unidoc[RouteResult] future transformation chain.

Please note that the `complete` directive has multiple variants, like 

## Example

Scala
:  @@snip [RouteDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/RouteDirectivesExamplesSpec.scala) { #complete-examples }

Java
:  @@snip [RouteDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/RouteDirectivesExamplesTest.java) { #complete }
