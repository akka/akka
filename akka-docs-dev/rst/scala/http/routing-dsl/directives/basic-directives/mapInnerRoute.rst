.. _-mapInnerRoute-:

mapInnerRoute
=============

Changes the execution model of the inner route by wrapping it with arbitrary logic.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapInnerRoute

Description
-----------

The ``mapInnerRoute`` directive is used as a building block for :ref:`Custom Directives` to replace the inner route
with any other route. Usually, the returned route wraps the original one with custom execution logic.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapInnerRoute
