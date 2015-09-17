.. _-overrideStatusCode-:

overrideStatusCode
==================

Overrides the status code of all responses coming back from its inner route with the given one.


Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/RespondWithDirectives.scala
   :snippet: overrideStatusCode


Description
-----------

This directive transforms ``HttpResponse`` and ``ChunkedResponseStart`` messages coming back from its inner route by
unconditionally overriding the status code with the given one.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/RespondWithDirectivesExamplesSpec.scala
   :snippet: overrideStatusCode-examples