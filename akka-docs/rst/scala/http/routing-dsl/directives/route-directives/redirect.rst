.. _-redirect-:

redirect
========

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala
   :snippet: redirect


Description
-----------
Completes the request with a redirection response to a given targer URI and of a given redirection type (status code).

``redirect`` is a convenience helper for completing the request with a redirection response.
It is equivalent to this snippet relying on the ``complete`` directive:

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala
   :snippet: red-impl


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/RouteDirectivesExamplesSpec.scala
   :snippet: redirect-examples