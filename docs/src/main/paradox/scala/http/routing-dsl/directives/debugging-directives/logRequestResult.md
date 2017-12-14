# logRequestResult

## Signature

```scala
def logRequestResult(marker: String)(implicit log: LoggingContext): Directive0
def logRequestResult(marker: String, level: LogLevel)(implicit log: LoggingContext): Directive0
def logRequestResult(show: HttpRequest => RouteResult => Option[LogEntry])(implicit log: LoggingContext): Directive0
```

The signature shown is simplified, the real signature uses magnets. <a id="^1" href="#1">[1]</a>

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

## Description

Logs both, the request and the response using the @unidoc[LoggingAdapter] of the @unidoc[RequestContext].

This directive is a combination of @ref[logRequest](logRequest.md) and @ref[logResult](logResult.md).

See @ref[logRequest](logRequest.md) for the general description how these directives work.

## Example

@@snip [DebuggingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala) { #logRequestResult }

## Building Advanced Directives

This example will showcase the advanced logging using the @unidoc[DebuggingDirectives].
The built *logResponseTime* directive will log the request time (or rejection reason):

Scala
:  @@snip [DebuggingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala) { #logRequestResultWithResponseTime }

Java
:  @@snip [DebuggingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logRequestResultWithResponseTime }
