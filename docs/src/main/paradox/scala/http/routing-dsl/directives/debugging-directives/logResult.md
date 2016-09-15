<a id="logresult"></a>
# logResult

## Signature

```
def logResult(marker: String)(implicit log: LoggingContext): Directive0
def logResult(marker: String, level: LogLevel)(implicit log: LoggingContext): Directive0
def logResult(show: RouteResult => String)(implicit log: LoggingContext): Directive0
def logResult(show: RouteResult => LogEntry)(implicit log: LoggingContext): Directive0
def logResult(magnet: LoggingMagnet[RouteResult => Unit])(implicit log: LoggingContext): Directive0
```

The signature shown is simplified, the real signature uses magnets. <a id="^1" href="#1">[1]</a>

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

## Description

Logs the response.

See @ref[logRequest](logRequest.md#logrequest) for the general description how these directives work. This directive is different
as it requires a `LoggingMagnet[RouteResult => Unit]`. Instead of just logging `HttpResponses`, `logResult` is able to
log any @ref[RouteResult](../../routes.md#routeresult) coming back from the inner route.

Use `logRequest` for logging the request, or `logRequestResult` for logging both.

## Example

@@snip [DebuggingDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala) { #logResult }