.. _-deleteCookie-:

deleteCookie
============

Adds a header to the response to request the removal of the cookie with the given name on the client.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/CookieDirectives.scala
   :snippet: deleteCookie

Description
-----------

Use the :ref:`-setCookie-` directive to update a cookie.

Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/CookieDirectivesExamplesSpec.scala
   :snippet: deleteCookie
