.. _-parameterMultiMap-java-:

parameterMultiMap
=================

Description
-----------

Extracts all parameters at once as a multi-map of type ``Map<String, List<String>>`` mapping
a parameter name to a list of all its values.

This directive can be used if parameters can occur several times.

The order of values is *not* specified.

See :ref:`which-parameter-directive-java` to understand when to use which directive.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java#parameterMultiMap
