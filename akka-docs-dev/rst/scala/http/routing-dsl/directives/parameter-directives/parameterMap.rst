.. _-parameterMap-:

parameterMap
============

Extracts all parameters at once as a ``Map[String, String]`` mapping parameter names to
parameter values.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ParameterDirectives.scala
   :snippet: parameterMap

Description
-----------

If a query contains a parameter value several times, the map will contain the last one.

See :ref:`which-parameter-directive` for other
choices.


Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: parameterMap
