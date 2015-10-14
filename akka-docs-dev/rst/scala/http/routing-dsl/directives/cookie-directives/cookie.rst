.. _-cookie-:

cookie
======

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CookieDirectives.scala
   :snippet: cookie

Description
-----------
Extracts a cookie with a given name from a request or otherwise rejects the request with a ``MissingCookieRejection`` if
the cookie is missing.

Use the :ref:`-optionalCookie-` directive instead if you want to support missing cookies in your inner route.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/CookieDirectivesExamplesSpec.scala
   :snippet: cookie
