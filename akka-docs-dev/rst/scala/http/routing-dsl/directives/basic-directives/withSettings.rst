.. _-withSettings-:

withSettings
============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: withSettings

Description
-----------

Allows running an inner route using an alternative :class:`RoutingSettings` in place of the default one.

The execution context can be extracted in an inner route using :ref:`-extractSettings-` directly,
or used by directives which internally extract the materializer without sufracing this fact in the API.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: withSettings-0
