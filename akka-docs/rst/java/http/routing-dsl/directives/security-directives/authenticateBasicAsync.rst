.. _-authenticateBasicAsync-java-:

authenticateBasicAsync
======================
Wraps the inner route with Http Basic authentication support using a given ``AsyncAuthenticator<T>``.

Description
-----------
This variant of the :ref:`-authenticateBasic-java-` directive returns a ``Future<Optional<T>>`` which allows freeing up the routing
layer of Akka HTTP, freeing it for other requests. It should be used whenever an authentication is expected to take
a longer amount of time (e.g. looking up the user in a database).

In case the returned option is an empty ``Optional`` the request is rejected with a :class:`AuthenticationFailedRejection`,
which by default is mapped to an ``401 Unauthorized`` response.

Standard HTTP-based authentication which uses the ``WWW-Authenticate`` header containing challenge data and
``Authorization`` header for receiving credentials is implemented in subclasses of ``HttpAuthenticator``.

See :ref:`credentials-and-timing-attacks-java` for details about verifying the secret.

.. warning::
  Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.

.. _HTTP Basic Authentication: https://en.wikipedia.org/wiki/Basic_auth

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
