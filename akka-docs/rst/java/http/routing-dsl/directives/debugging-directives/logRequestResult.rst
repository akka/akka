.. _-logRequestResult-java-:

logRequestResult
================

Description
-----------
Logs both, the request and the response.

This directive is a combination of :ref:`-logRequest-java-` and :ref:`-logResult-java-`.

See :ref:`-logRequest-java-` for the general description how these directives work.

Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java#logRequestResult

Longer Example
--------------

This example shows how to log the response time of the request using the Debugging Directive

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java#logRequestResultWithResponseTime
