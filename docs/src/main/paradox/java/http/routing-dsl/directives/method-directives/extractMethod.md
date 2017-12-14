# extractMethod

@@@ div { .group-scala }

## Signature

@@signature [MethodDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MethodDirectives.scala) { #extractMethod }

@@@

## Description

Extracts the @unidoc[HttpMethod] from the request context and provides it for use for other directives explicitly.

## Example

In the below example our route first matches all `GET` requests, and if an incoming request wasn't a `GET`,
the matching continues and the extractMethod route will be applied which we can use to programatically
print what type of request it was - independent of what actual HttpMethod it was:

@@snip [MethodDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MethodDirectivesExamplesTest.java) { #extractMethod }

## Custom Http Method

When you define a custom HttpMethod, you can define a route using extractMethod.

>
Scala
:  @@snip [CustomHttpMethodSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/CustomHttpMethodSpec.scala) { #application-custom }

Java
:  @@snip [CustomHttpMethodExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/CustomHttpMethodExamplesTest.java) { #customHttpMethod }
