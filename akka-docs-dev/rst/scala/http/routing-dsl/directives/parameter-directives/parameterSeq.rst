.. _-parameterSeq-:

parameterSeq
============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ParameterDirectives.scala
   :snippet: parameterSeq

Description
-----------
Extracts all parameters at once in the original order as (name, value) tuples of type ``(String, String)``.

This directive can be used if the exact order of parameters is important or if parameters can occur several times.

See :ref:`which-parameter-directive` to understand when to use which directive.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: parameterSeq
