.. _-extractRequestContext-:

extractRequestContext
=====================

Extracts the request's underlying :class:`RequestContext`.

This directive is used as a building block for most of the other directives,
which extract the context and by inspecting some of it's values can decide
what to do with the request - for example provide a value, or reject the request.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractRequestContext

Description
-----------

...

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: 0extractRequestContext
