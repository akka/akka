.. _-extractSettings-java-:

extractSettings
===============

Description
-----------

Extracts the :class:`RoutingSettings` from the :class:`RequestContext`.

By default the settings of the ``Http()`` extension running the route will be returned.
It is possible to override the settings for specific sub-routes by using the :ref:`-withSettings-java-` directive.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractSettings-examples
