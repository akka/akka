# authenticateBasicAsync

## Signature

@@signature [SecurityDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala) { #AsyncAuthenticator }

@@signature [SecurityDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala) { #authenticateBasicAsync }

## Description

Wraps the inner route with Http Basic authentication support using a given `AsyncAuthenticator[T]`.

This variant of the @ref[authenticateBasic](authenticateBasic.md) directive returns a `Future[Option[T]]` which allows freeing up the routing
layer of Akka HTTP, freeing it for other requests. It should be used whenever an authentication is expected to take
a longer amount of time (e.g. looking up the user in a database).

In case the returned option is `None` the request is rejected with a @unidoc[AuthenticationFailedRejection],
which by default is mapped to an `401 Unauthorized` response.

Standard HTTP-based authentication which uses the `WWW-Authenticate` header containing challenge data and
@unidoc[Authorization] header for receiving credentials is implemented in subclasses of `HttpAuthenticator`.

See @ref[Credentials and password timing attacks](index.md#credentials-and-timing-attacks-scala) for details about verifying the secret.

@@@ warning
Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.
@@@

## Example

Scala
:  @@snip [SecurityDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala) { #authenticateBasicAsync-0 }

Java
:  @@snip [SecurityDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/SecurityDirectivesExamplesTest.java) { #authenticateBasicAsync }
