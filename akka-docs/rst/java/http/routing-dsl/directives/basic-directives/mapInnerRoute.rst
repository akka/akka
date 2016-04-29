.. _-mapInnerRoute-java-:

mapInnerRoute
=============

Signature
---------
TODO: Add example snippet.
.. 
.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapInnerRoute

Description
-----------
Changes the execution model of the inner route by wrapping it with arbitrary logic.

The ``mapInnerRoute`` directive is used as a building block for :ref:`Custom Directives` to replace the inner route
with any other route. Usually, the returned route wraps the original one with custom execution logic.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapInnerRoute
