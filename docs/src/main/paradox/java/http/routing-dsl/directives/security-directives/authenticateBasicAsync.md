<a id="authenticatebasicasync-java"></a>
# authenticateBasicAsync

Wraps the inner route with Http Basic authentication support using a given `AsyncAuthenticator<T>`.

## Description

This variant of the @ref[authenticateBasic](authenticateBasic.md#authenticatebasic-java) directive returns a `Future<Optional<T>>` which allows freeing up the routing
layer of Akka HTTP, freeing it for other requests. It should be used whenever an authentication is expected to take
a longer amount of time (e.g. looking up the user in a database).

In case the returned option is an empty `Optional` the request is rejected with a `AuthenticationFailedRejection`,
which by default is mapped to an `401 Unauthorized` response.

Standard HTTP-based authentication which uses the `WWW-Authenticate` header containing challenge data and
`Authorization` header for receiving credentials is implemented in subclasses of `HttpAuthenticator`.

See @ref[Credentials and password timing attacks](index.md#credentials-and-timing-attacks-java) for details about verifying the secret.

@@@ warning
Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.
@@@

## Example

@@snip [SecurityDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/SecurityDirectivesExamplesTest.java) { #authenticateBasicAsync }
