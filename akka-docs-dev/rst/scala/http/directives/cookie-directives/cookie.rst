.. _-cookie-:

cookie
======

Extracts a cookie with a given name from a request or otherwise rejects the request with a ``MissingCookieRejection`` if
the cookie is missing.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/CookieDirectives.scala
   :snippet: cookie

Description
-----------

Use the :ref:`-optionalCookie-` directive instead if you want to support missing cookies in your inner route.


Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/CookieDirectivesExamplesSpec.scala
   :snippet: cookie
