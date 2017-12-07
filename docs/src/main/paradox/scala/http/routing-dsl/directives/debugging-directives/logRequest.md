# logRequest

## Signature

```scala
def logRequest(marker: String): Directive0
def logRequest(marker: String, level: LogLevel): Directive0
def logRequest(show: HttpRequest => String): Directive0
def logRequest(show: HttpRequest => LogEntry): Directive0
def logRequest(magnet: LoggingMagnet[HttpRequest => Unit]): Directive0
```

The signature shown is simplified, the real signature uses magnets. <a id="^1" href="#1">[1]</a>

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

## Description

Logs the request using the supplied `LoggingMagnet[HttpRequest => Unit]` using the @unidoc[LoggingAdapter] of the @unidoc[RequestContext]. The `LoggingMagnet` is a wrapped
function `HttpRequest => Unit` that can be implicitly created from the different constructors shown above. These
constructors build a `LoggingMagnet` from these components:

 * A marker to prefix each log message with.
 * A log level.
 * A `show` function that calculates a string representation for a request.
 * A function that creates a @unidoc[LogEntry] which is a combination of the elements above.

It is also possible to use any other function `HttpRequest => Unit` for logging by wrapping it with `LoggingMagnet`.
See the examples for ways to use the `logRequest` directive.

Use `logResult` for logging the response, or `logRequestResult` for logging both.

To change the logger, wrap this directive by @ref[withLog](../basic-directives/withLog.md).

## Example

Scala
:  @@snip [DebuggingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala) { #logRequest-0 }

Java
:  @@snip [DebuggingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logRequest }
