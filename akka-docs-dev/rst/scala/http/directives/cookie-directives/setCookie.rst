.. _-setCookie-:

setCookie
=========

Adds a header to the response to request the update of the cookie with the given name on the client.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/CookieDirectives.scala
   :snippet: setCookie

Description
-----------

Use the :ref:`-deleteCookie-` directive to delete a cookie.


Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/CookieDirectivesExamplesSpec.scala
   :snippet: setCookie
