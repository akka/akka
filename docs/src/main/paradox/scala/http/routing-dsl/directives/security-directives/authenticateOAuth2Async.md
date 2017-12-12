# authenticateOAuth2Async

## Signature

@@signature [SecurityDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala) { #AsyncAuthenticator }

@@signature [SecurityDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala) { #authenticateOAuth2Async }

## Description

Wraps the inner route with OAuth Bearer Token authentication support using a given `AsyncAuthenticator[T]`.

Provides support for extracting the so-called "*Bearer Token*" from the @unidoc[Authorization] HTTP Header,
which is used to initiate an OAuth2 authorization.

@@@ warning
This directive does not implement the complete OAuth2 protocol, but instead enables implementing it,
by extracting the needed token from the HTTP headers.
@@@

Given a function returning `Some[T]` upon successful authentication and `None` otherwise,
respectively applies the inner route or rejects the request with a @unidoc[AuthenticationFailedRejection] rejection,
which by default is mapped to an `401 Unauthorized` response.

See also @ref[authenticateOAuth2](authenticateOAuth2.md) if the authorization operation is rather quick, and does not have to execute asynchronously.

See @ref[Credentials and password timing attacks](index.md#credentials-and-timing-attacks-scala) for details about verifying the secret.

For more information on how OAuth2 works see [RFC 6750](https://tools.ietf.org/html/rfc6750).

## Example

Usage in code is exactly the same as @ref[authenticateBasicAsync](authenticateBasicAsync.md),
with the difference that one must validate the token as OAuth2 dictates (which is currently not part of Akka HTTP itself).
