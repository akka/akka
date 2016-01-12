.. _-withMaterializer-:

withMaterializer
================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: withMaterializer

Description
-----------

Allows running an inner route using an alternative ``Materializer`` in place of the default one.

The materializer can be extracted in an inner route using :ref:`-extractMaterializer-` directly,
or used by directives which internally extract the materializer without sufracing this fact in the API
(e.g. responding with a Chunked entity).

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: withMaterializer-0
