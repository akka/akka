.. _-requestEntityEmpty-:

requestEntityEmpty
==================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala
   :snippet: requestEntityEmpty


Description
-----------
A filter that checks if the request entity is empty and only then passes processing to the inner route.
Otherwise, the request is rejected.


See also :ref:`-requestEntityPresent-` for the opposite effect.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: requestEntityEmptyPresent-example
