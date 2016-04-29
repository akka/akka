.. _-parameterMap-java-:

parameterMap
============
Extracts all parameters at once as a ``Map[String, String]`` mapping parameter names to parameter values.

Description
-----------
If a query contains a parameter value several times, the map will contain the last one.

See also :ref:`which-parameter-directive-java` to understand when to use which directive.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: parameterMap
