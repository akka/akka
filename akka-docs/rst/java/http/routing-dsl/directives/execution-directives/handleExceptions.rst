.. _-handleExceptions-java-:

handleExceptions
================

Description
-----------
Catches exceptions thrown by the inner route and handles them using the specified ``ExceptionHandler``.

Using this directive is an alternative to using a global implicitly defined ``ExceptionHandler`` that
applies to the complete route.

See :ref:`exception-handling-java` for general information about options for handling exceptions.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/ExecutionDirectivesExamplesTest.java#handleExceptions
