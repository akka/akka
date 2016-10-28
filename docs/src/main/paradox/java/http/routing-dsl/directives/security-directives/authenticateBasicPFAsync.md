<a id="authenticatebasicpfasync-java"></a>
# authenticateBasicPFAsync

Wraps the inner route with Http Basic authentication support using a given `AsyncAuthenticatorPF<T>`.

## Description

Provides support for handling [HTTP Basic Authentication](https://en.wikipedia.org/wiki/Basic_auth).

Refer to @ref[authenticateBasic](authenticateBasic.md#authenticatebasic-java) for a detailed description of this directive.

Its semantics are equivalent to `authenticateBasicPF` 's, where not handling a case in the Partial Function (PF)
leaves the request to be rejected with a `AuthenticationFailedRejection` rejection.

See @ref[Credentials and password timing attacks](index.md#credentials-and-timing-attacks-java) for details about verifying the secret.

@@@ warning
Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.
@@@

## Example

@@snip [SecurityDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/SecurityDirectivesExamplesTest.java) { #authenticateBasicPFAsync }
