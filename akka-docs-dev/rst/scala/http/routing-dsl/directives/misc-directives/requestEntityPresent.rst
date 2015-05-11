.. _-requestEntityPresent-:

requestEntityPresent
====================

A simple filter that checks if the request entity is present and only then passes processing to the inner route.
Otherwise, the request is rejected.


Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala
   :snippet: requestEntityPresent


Description
-----------

The opposite filter is available as ``requestEntityEmpty``.


Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: requestEntityEmptyPresent-example
