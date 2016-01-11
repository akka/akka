.. _-mapSettings-:

mapSettings
===========

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapSettings

Description
-----------

Transforms the ``RoutingSettings`` with a ``RoutingSettings ⇒ RoutingSettings`` function.

See also :ref:`-withSettings-` or :ref:`-extractSettings-`.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: withSettings-0
