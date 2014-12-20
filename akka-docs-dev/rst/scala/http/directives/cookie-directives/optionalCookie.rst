.. _-optionalCookie-:

optionalCookie
==============

Extracts an optional cookie with a given name from a request.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/CookieDirectives.scala
   :snippet: optionalCookie

Description
-----------

Use the :ref:`-cookie-` directive instead if the inner route does not handle a missing cookie.


Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/CookieDirectivesExamplesSpec.scala
   :snippet: optionalCookie
