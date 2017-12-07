# logResult

## Signature

```scala
def logResult(marker: String): Directive0
def logResult(marker: String, level: LogLevel): Directive0
def logResult(show: RouteResult => String): Directive0
def logResult(show: RouteResult => LogEntry): Directive0
def logResult(magnet: LoggingMagnet[RouteResult => Unit]): Directive0
```

The signature shown is simplified, the real signature uses magnets. <a id="^1" href="#1">[1]</a>

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

## Description

Logs the response using the @unidoc[LoggingAdapter] of the @unidoc[RequestContext].

See @ref[logRequest](logRequest.md) for the general description how these directives work. This directive is different
as it requires a `LoggingMagnet[RouteResult => Unit]`. Instead of just logging `HttpResponses`, `logResult` is able to
log any @ref[RouteResult](../../routes.md#routeresult) coming back from the inner route.

Use `logRequest` for logging the request, or `logRequestResult` for logging both.

## Example

Scala
:  @@snip [DebuggingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala) { #logResult }

Java
:  @@snip [DebuggingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logResult }
