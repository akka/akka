.. _-logRequest-java-:

logRequest
==========

Description
-----------

Logs the request. The directive is available with the following parameters:

  * A marker to prefix each log message with.
  * A log level.
  * A function that creates a :class:``LogEntry`` from the :class:``HttpRequest``

Use ``logResult`` for logging the response, or ``logRequestResult`` for logging both.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala
   :snippet: logRequest-0
