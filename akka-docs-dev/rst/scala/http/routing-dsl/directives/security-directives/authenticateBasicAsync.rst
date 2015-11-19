.. _-authenticateBasicAsync-:

authenticateBasicAsync
======================

Signature
---------

.. includecode:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala#async-authenticator

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala
   :snippet: authenticateBasicAsync

Description
-----------
Wraps the inner route with Http Basic authentication support using a given ``AsyncAuthenticator[T]``.

This variant of the :ref:`-authenticateBasic-` directive returns a ``Future[Option[T]]`` which allows freeing up the routing
layer of Akka HTTP, freeing it for other requests. It should be used whenever an authentication is expected to take
a longer amount of time (e.g. looking up the user in a database).

In case the returned option is ``None`` the request is rejected with a :class:`AuthenticationFailedRejection`,
which by default is mapped to an ``401 Unauthorized`` response.

Standard HTTP-based authentication which uses the ``WWW-Authenticate`` header containing challenge data and
``Authorization`` header for receiving credentials is implemented in subclasses of ``HttpAuthenticator``.

See :ref:`credentials-and-timing-attacks-scala` for details about verifying the secret.

.. warning::
  Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.

.. _HTTP Basic Authentication: https://en.wikipedia.org/wiki/Basic_auth

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala
   :snippet: authenticateBasicAsync-0
