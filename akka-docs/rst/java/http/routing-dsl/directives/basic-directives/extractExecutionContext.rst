.. _-extractExecutionContext-java-:

extractExecutionContext
=======================

Signature
---------
TODO: Add example snippet.
.. 
.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractExecutionContext

Description
-----------

Extracts the ``ExecutionContext`` from the ``RequestContext``.

See :ref:`-withExecutionContext-java-` to see how to customise the execution context provided for an inner route.

See :ref:`-extract-java-` to learn more about how extractions work.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractExecutionContext-0
