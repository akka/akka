.. _-parameterMap-:

parameterMap
============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ParameterDirectives.scala
   :snippet: parameterMap

Description
-----------
Extracts all parameters at once as a ``Map[String, String]`` mapping parameter names to parameter values.

If a query contains a parameter value several times, the map will contain the last one.

See also :ref:`which-parameter-directive` to understand when to use which directive.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: parameterMap
