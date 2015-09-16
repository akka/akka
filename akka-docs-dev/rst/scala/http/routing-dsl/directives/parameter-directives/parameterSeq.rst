.. _-parameterSeq-:

parameterSeq
============

Extracts all parameters at once in the original order as (name, value) tuples of type ``(String, String)``.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ParameterDirectives.scala
   :snippet: parameterSeq

Description
-----------

This directive can be used if the exact order of parameters is important or if parameters can
occur several times.

See :ref:`which-parameter-directive` for other choices.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: parameterSeq
