.. _-withExecutionContext-java-:

withExecutionContext
====================

Description
-----------

Allows running an inner route using an alternative ``ExecutionContextExecutor`` in place of the default one.

The execution context can be extracted in an inner route using :ref:`-extractExecutionContext-java-` directly,
or used by directives which internally extract the materializer without sufracing this fact in the API.


Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#withExecutionContext
