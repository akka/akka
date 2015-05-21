.. _-handleExceptions-:

handleExceptions
================

Catches exceptions thrown by the inner route and handles them using the specified ``ExceptionHandler``.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/ExecutionDirectives.scala
   :snippet: handleExceptions

Description
-----------

Using this directive is an alternative to using a global implicitly defined ``ExceptionHandler`` that
applies to the complete route.

See :ref:`Exception Handling` for general information about options for handling exceptions.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ExecutionDirectivesExamplesSpec.scala
   :snippet: handleExceptions
