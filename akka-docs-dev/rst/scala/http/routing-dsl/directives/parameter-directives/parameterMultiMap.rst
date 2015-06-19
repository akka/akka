.. _-parameterMultiMap-:

parameterMultiMap
=================

Extracts all parameters at once as a multi-map of type ``Map[String, List[String]`` mapping
a parameter name to a list of all its values.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ParameterDirectives.scala
   :snippet: parameterMultiMap

Description
-----------

This directive can be used if parameters can occur several times. The order of values is
not specified.

See :ref:`which-parameter-directive` for other choices.

Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: parameterMultiMap
