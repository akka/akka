.. _-logRequestResult-java-:

logRequestResult
================

Signature
---------

::

    def logRequestResult(marker: String)(implicit log: LoggingContext): Directive0
    def logRequestResult(marker: String, level: LogLevel)(implicit log: LoggingContext): Directive0
    def logRequestResult(show: HttpRequest => HttpResponsePart => Option[LogEntry])
                          (implicit log: LoggingContext): Directive0
    def logRequestResult(show: HttpRequest => Any => Option[LogEntry])(implicit log: LoggingContext): Directive0

The signature shown is simplified, the real signature uses magnets. [1]_

.. [1] See `The Magnet Pattern`_ for an explanation of magnet-based overloading.
.. _`The Magnet Pattern`: http://spray.io/blog/2012-12-13-the-magnet-pattern/

Description
-----------
Logs both, the request and the response.

This directive is a combination of :ref:`-logRequest-java-` and :ref:`-logResult-java-`.

See :ref:`-logRequest-java-` for the general description how these directives work.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala
   :snippet: logRequestResult
