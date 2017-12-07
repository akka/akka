# complete

## Signature

```scala
def complete[T :ToResponseMarshaller](value: T): StandardRoute
def complete(response: HttpResponse): StandardRoute
def complete(status: StatusCode): StandardRoute
def complete[T :Marshaller](status: StatusCode, value: T): StandardRoute
def complete[T :Marshaller](status: Int, value: T): StandardRoute
def complete[T :Marshaller](status: StatusCode, headers: Seq[HttpHeader], value: T): StandardRoute
def complete[T :Marshaller](status: Int, headers: Seq[HttpHeader], value: T): StandardRoute
```

The signature shown is simplified, the real signature uses magnets. <a id="^1" href="#1">[1]</a>

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

## Description

Completes the request using the given argument(s).

`complete` uses the given arguments to construct a @unidoc[Route] which simply calls `complete` on the @unidoc[RequestContext]
with the respective @unidoc[HttpResponse] instance.
Completing the request will send the response "back up" the route structure where all the logic runs that wrapping
directives have potentially chained into the @ref[RouteResult](../../routes.md#routeresult) future transformation chain.

## Example

Scala
:  @@snip [RouteDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/RouteDirectivesExamplesSpec.scala) { #complete-examples }

Java
:  @@snip [RouteDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/RouteDirectivesExamplesTest.java) { #complete }
