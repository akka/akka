.. _-logResult-:

logResult
=========

Logs the response.

Signature
---------

::

    def logResult(marker: String)(implicit log: LoggingContext): Directive0
    def logResult(marker: String, level: LogLevel)(implicit log: LoggingContext): Directive0
    def logResult(show: Any => String)(implicit log: LoggingContext): Directive0
    def logResult(show: Any => LogEntry)(implicit log: LoggingContext): Directive0
    def logResult(magnet: LoggingMagnet[Any => Unit])(implicit log: LoggingContext): Directive0

The signature shown is simplified, the real signature uses magnets. [1]_

.. [1] See `The Magnet Pattern`_ for an explanation of magnet-based overloading.
.. _`The Magnet Pattern`: /blog/2012-12-13-the-magnet-pattern/

Description
-----------

See ``logRequest`` for the general description how these directives work. This directive is different
as it requires a ``LoggingMagnet[Any => Unit]``. Instead of just logging ``HttpResponses``, ``logResult`` is able to
log any :ref:`RouteResult` coming back from the inner route.

Use ``logRequest`` for logging the request, or ``logRequestResult`` for logging both.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala
   :snippet: logResult
