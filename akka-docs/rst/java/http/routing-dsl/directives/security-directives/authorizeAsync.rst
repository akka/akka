.. _-authorizeAsync-java-:

authorizeAsync
==============
Applies the given authorization check to the request.

Description
-----------

The user-defined authorization check can either be supplied as a ``=> Future[Boolean]`` value which is calculated
just from information out of the lexical scope, or as a function ``RequestContext => Future[Boolean]`` which can also
take information from the request itself into account.

If the check returns ``true`` or the ``Future`` is failed the request is passed on to the inner route unchanged,
otherwise an ``AuthorizationFailedRejection`` is created, triggering a ``403 Forbidden`` response by default
(the same as in the case of an ``AuthenticationFailedRejection``).

In a common use-case you would check if a user (e.g. supplied by any of the ``authenticate*`` family of directives,
e.g. :ref:`-authenticateBasic-java-`) is allowed to access the inner routes, e.g. by checking if the user has the needed permissions.

See also :ref:`-authorize-java-` for the synchronous version of this directive.

.. note::
  See also :ref:`authentication-vs-authorization-java` to understand the differences between those.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
