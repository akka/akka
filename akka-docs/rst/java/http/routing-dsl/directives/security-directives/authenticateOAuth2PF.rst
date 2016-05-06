.. _-authenticateOAuth2PF-java-:

authenticateOAuth2PF
====================
Wraps the inner route with OAuth Bearer Token authentication support using a given ``AuthenticatorPF<T>``.

Description
-----------
Provides support for extracting the so-called "*Bearer Token*" from the :class:`Authorization` HTTP Header,
which is used to initiate an OAuth2 authorization.

.. warning::
  This directive does not implement the complete OAuth2 protocol, but instead enables implementing it,
  by extracting the needed token from the HTTP headers.

Refer to :ref:`-authenticateOAuth2-java-` for a detailed description of this directive.

Its semantics are equivalent to ``authenticateOAuth2PF`` 's, where not handling a case in the Partial Function (PF)
leaves the request to be rejected with a :class:`AuthenticationFailedRejection` rejection.

Longer-running authentication tasks (like looking up credentials in a database) should use the :ref:`-authenticateOAuth2Async-java-`
variant of this directive which allows it to run without blocking routing layer of Akka HTTP, freeing it for other requests.

See :ref:`credentials-and-timing-attacks-java` for details about verifying the secret.

For more information on how OAuth2 works see `RFC 6750`_.

.. _RFC 6750: https://tools.ietf.org/html/rfc6750

Example
-------

Usage in code is exactly the same as :ref:`-authenticateBasicPF-java-`,
with the difference that one must validate the token as OAuth2 dictates (which is currently not part of Akka HTTP itself).
