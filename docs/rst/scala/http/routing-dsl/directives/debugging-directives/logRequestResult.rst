.. _-logRequestResult-:

logRequestResult
================

Signature
---------

::

    def logRequestResult(marker: String)(implicit log: LoggingContext): Directive0
    def logRequestResult(marker: String, level: LogLevel)(implicit log: LoggingContext): Directive0
    def logRequestResult(show: HttpRequest => RouteResult => Option[LogEntry])(implicit log: LoggingContext): Directive0

The signature shown is simplified, the real signature uses magnets. [1]_

.. [1] See `The Magnet Pattern`_ for an explanation of magnet-based overloading.
.. _`The Magnet Pattern`: http://spray.io/blog/2012-12-13-the-magnet-pattern/

Description
-----------
Logs both, the request and the response.

This directive is a combination of :ref:`-logRequest-` and :ref:`-logResult-`.

See :ref:`-logRequest-` for the general description how these directives work.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala
   :snippet: logRequestResult


Building Advanced Directives
----------------------------

This example will showcase the advanced logging using the ``DebuggingDirectives``.
The built `logResponseTime` directive will log the request time (or rejection reason):

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala
   :snippet: logRequestResultWithResponseTime
