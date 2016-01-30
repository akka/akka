.. _-authenticateOAuth2PFAsync-:

authenticateOAuth2PFAsync
=========================

Wraps the inner route with OAuth Bearer Token authentication support using a given ``AsyncAuthenticatorPF[T]``.

Signature
---------

.. includecode:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala#authenticator

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala
   :snippet: authenticateOAuth2PFAsync

Description
-----------

Provides support for extracting the so-called "*Bearer Token*" from the :class:`Authorization` HTTP Header,
which is used to initiate an OAuth2 authorization.

.. warning::
  This directive does not implement the complete OAuth2 protocol, but instead enables implementing it,
  by extracting the needed token from the HTTP headers.

Refer to :ref:`-authenticateOAuth2-` for a detailed description of this directive.

Its semantics are equivalent to ``authenticateOAuth2PF`` 's, where not handling a case in the Partial Function (PF)
leaves the request to be rejected with a :class:`AuthenticationFailedRejection` rejection.

See also :ref:`-authenticateOAuth2PF-` if the authorization operation is rather quick, and does not have to execute asynchronously.

See :ref:`credentials-and-timing-attacks-scala` for details about verifying the secret.

For more information on how OAuth2 works see `RFC 6750`_.

.. _RFC 6750: https://tools.ietf.org/html/rfc6750


Example
-------

Usage in code is exactly the same as :ref:`-authenticateBasicPFAsync-`,
with the difference that one must validate the token as OAuth2 dictates (which is currently not part of Akka HTTP itself).
