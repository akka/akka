.. _-parameterList-java-:

parameterList
=============

Description
-----------
Extracts all parameters at once in the original order as (name, value) tuples of type ``Map.Entry<String, String>``.

This directive can be used if the exact order of parameters is important or if parameters can occur several times.

See :ref:`which-parameter-directive-java` to understand when to use which directive.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java#parameterSeq
