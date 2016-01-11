.. _-handleExceptions-:

handleExceptions
================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ExecutionDirectives.scala
   :snippet: handleExceptions

Description
-----------
Catches exceptions thrown by the inner route and handles them using the specified ``ExceptionHandler``.

Using this directive is an alternative to using a global implicitly defined ``ExceptionHandler`` that
applies to the complete route.

See :ref:`exception-handling-scala` for general information about options for handling exceptions.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ExecutionDirectivesExamplesSpec.scala
   :snippet: handleExceptions
