.. _-authenticateBasicPF-java-:

authenticateBasicPF
===================
Wraps the inner route with Http Basic authentication support using a given ``AuthenticatorPF<T>``.

Description
-----------
Provides support for handling `HTTP Basic Authentication`_.

Refer to :ref:`-authenticateBasic-java-` for a detailed description of this directive.

Its semantics are equivalent to ``authenticateBasicPF`` 's, where not handling a case in the Partial Function (PF)
leaves the request to be rejected with a :class:`AuthenticationFailedRejection` rejection.

Longer-running authentication tasks (like looking up credentials in a database) should use :ref:`-authenticateBasicAsync-java-`
or :ref:`-authenticateBasicPFAsync-java-` if you prefer to use the ``PartialFunction`` syntax.

See :ref:`credentials-and-timing-attacks-java` for details about verifying the secret.

.. warning::
  Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.

.. _HTTP Basic Authentication: https://en.wikipedia.org/wiki/Basic_auth

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
