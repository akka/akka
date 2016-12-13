<a id="extractcredentials"></a>
# extractCredentials

## Signature

@@signature [SecurityDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala) { #extractCredentials }

## Description

Extracts the potentially present `HttpCredentials` provided with the request's `Authorization` header,
which can be then used to implement some custom authentication or authorization logic.

See @ref[Credentials and password timing attacks](index.md#credentials-and-timing-attacks-scala) for details about verifying the secret.

## Example

@@snip [SecurityDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala) { #extractCredentials0 }