.. _-requestEntityEmpty-:

requestEntityEmpty
==================

A filter that checks if the request entity is empty and only then passes processing to the inner route.
Otherwise, the request is rejected.


Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala
   :snippet: requestEntityEmpty


Description
-----------

The opposite filter is available as ``requestEntityPresent``.


Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: requestEntityEmptyPresent-example
