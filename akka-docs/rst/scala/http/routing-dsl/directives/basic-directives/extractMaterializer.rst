.. _-extractMaterializer-:

extractMaterializer
===================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractMaterializer

Description
-----------

Extracts the ``Materializer`` from the ``RequestContext``, which can be useful when you want to run an
Akka Stream directly in your route.

See also :ref:`-withMaterializer-` to see how to customise the used materializer for specific inner routes.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractMaterializer-0
