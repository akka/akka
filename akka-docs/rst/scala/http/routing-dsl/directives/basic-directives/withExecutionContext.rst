.. _-withExecutionContext-:

withExecutionContext
====================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: withExecutionContext

Description
-----------

Allows running an inner route using an alternative ``ExecutionContext`` in place of the default one.

The execution context can be extracted in an inner route using :ref:`-extractExecutionContext-` directly,
or used by directives which internally extract the materializer without sufracing this fact in the API.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: withExecutionContext-0
