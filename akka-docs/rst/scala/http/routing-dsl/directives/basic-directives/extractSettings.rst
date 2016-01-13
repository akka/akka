.. _-extractSettings-:

extractSettings
===============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractSettings

Description
-----------

Extracts the ``RoutingSettings`` from the :class:`RequestContext`.

By default the settings of the ``Http()`` extension running the route will be returned.
It is possible to override the settings for specific sub-routes by using the :ref:`-withSettings-` directive.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractSettings-examples
