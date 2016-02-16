.. _-authenticateBasicPFAsync-:

authenticateBasicPFAsync
========================

Signature
---------

.. includecode:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala#async-authenticator-pf

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala
   :snippet: authenticateBasicPFAsync

Description
-----------
Wraps the inner route with Http Basic authentication support using a given ``AsyncAuthenticatorPF[T]``.

Provides support for handling `HTTP Basic Authentication`_.

Refer to :ref:`-authenticateBasic-` for a detailed description of this directive.

Its semantics are equivalent to ``authenticateBasicPF`` 's, where not handling a case in the Partial Function (PF)
leaves the request to be rejected with a :class:`AuthenticationFailedRejection` rejection.

See :ref:`credentials-and-timing-attacks-scala` for details about verifying the secret.

.. warning::
  Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.

.. _HTTP Basic Authentication: https://en.wikipedia.org/wiki/Basic_auth

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala
   :snippet: authenticateBasicPFAsync-0
