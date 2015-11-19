.. _-authenticateBasic-:

authenticateBasic
=================

Signature
---------

.. includecode:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala#authenticator

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala
   :snippet: authenticateBasic

Description
-----------
Wraps the inner route with Http Basic authentication support using a given ``Authenticator[T]``.

Provides support for handling `HTTP Basic Authentication`_.

Given a function returning ``Some[T]`` upon successful authentication and ``None`` otherwise,
respectively applies the inner route or rejects the request with a :class:`AuthenticationFailedRejection` rejection,
which by default is mapped to an ``401 Unauthorized`` response.

Longer-running authentication tasks (like looking up credentials in a database) should use the :ref:`-authenticateBasicAsync-`
variant of this directive which allows it to run without blocking routing layer of Akka HTTP, freeing it for other requests.

Standard HTTP-based authentication which uses the ``WWW-Authenticate`` header containing challenge data and
``Authorization`` header for receiving credentials is implemented in subclasses of ``HttpAuthenticator``.

See :ref:`credentials-and-timing-attacks-scala` for details about verifying the secret.

.. warning::
  Make sure to use basic authentication only over SSL/TLS because credentials are transferred in plaintext.

.. _HTTP Basic Authentication: https://en.wikipedia.org/wiki/Basic_auth

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala
   :snippet: authenticateBasic-0
