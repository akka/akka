.. _-authenticateBasic-java-:

authenticateBasic
=================
Wraps the inner route with Http Basic authentication support using a given ``Authenticator<T>``.

Description
-----------
Provides support for handling `HTTP Basic Authentication`_.

Given a function returning an ``Optional<T>`` with a value upon successful authentication and an empty ``Optional<T>`` otherwise,
respectively applies the inner route or rejects the request with a :class:`AuthenticationFailedRejection` rejection,
which by default is mapped to an ``401 Unauthorized`` response.

Longer-running authentication tasks (like looking up credentials in a database) should use the :ref:`-authenticateBasicAsync-java-`
variant of this directive which allows it to run without blocking routing layer of Akka HTTP, freeing it for other requests.

Standard HTTP-based authentication which uses the ``WWW-Authenticate`` header containing challenge data and
``Authorization`` header for receiving credentials is implemented in subclasses of ``HttpAuthenticator``.

See :ref:`credentials-and-timing-attacks-java` for details about verifying the secret.

.. warning::
  Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.

.. _HTTP Basic Authentication: https://en.wikipedia.org/wiki/Basic_auth

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
