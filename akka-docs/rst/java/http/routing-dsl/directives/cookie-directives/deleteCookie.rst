.. _-deleteCookie-:

deleteCookie
============

Signature
---------
TODO: Add example snippet.
.. 
.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CookieDirectives.scala
   :snippet: deleteCookie

Description
-----------
Adds a header to the response to request the removal of the cookie with the given name on the client.

Use the :ref:`-setCookie-` directive to update a cookie.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/CookieDirectivesExamplesSpec.scala
   :snippet: deleteCookie
