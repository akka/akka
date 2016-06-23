.. _-parameterMap-java-:

parameterMap
============
Extracts all parameters at once as a ``Map<String, String>`` mapping parameter names to parameter values.

Description
-----------
If a query contains a parameter value several times, the map will contain the last one.

See also :ref:`which-parameter-directive-java` to understand when to use which directive.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java#parameterMap
