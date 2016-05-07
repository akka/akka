.. _-authorize-:

authorize
=========

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala
   :snippet: authorize

Description
-----------
Applies the given authorization check to the request.

The user-defined authorization check can either be supplied as a ``=> Boolean`` value which is calculated
just from information out of the lexical scope, or as a function ``RequestContext => Boolean`` which can also
take information from the request itself into account.

If the check returns ``true`` the request is passed on to the inner route unchanged, otherwise an
``AuthorizationFailedRejection`` is created, triggering a ``403 Forbidden`` response by default
(the same as in the case of an ``AuthenticationFailedRejection``).

In a common use-case you would check if a user (e.g. supplied by any of the ``authenticate*`` family of directives,
e.g. :ref:`-authenticateBasic-`) is allowed to access the inner routes, e.g. by checking if the user has the needed permissions.

See also :ref:`-authorizeAsync-` for the asynchronous version of this directive.

.. note::
  See also :ref:`authentication-vs-authorization-scala` to understand the differences between those.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala
   :snippet: 0authorize-0
