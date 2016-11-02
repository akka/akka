<a id="extractmethod"></a>
# extractMethod

## Signature

@@signature [MethodDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MethodDirectives.scala) { #extractMethod }

## Description

Extracts the `HttpMethod` from the request context and provides it for use for other directives explicitly.

## Example

In the below example our route first matches all `GET` requests, and if an incoming request wasn't a `GET`,
the matching continues and the extractMethod route will be applied which we can use to programatically
print what type of request it was - independent of what actual HttpMethod it was:

@@snip [MethodDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/MethodDirectivesExamplesSpec.scala) { #extractMethod-example }

## Custom Http Method

When you define a custom HttpMethod, you can define a route using extractMethod.

@@snip [CustomHttpMethodSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CustomHttpMethodSpec.scala) { #application-custom }