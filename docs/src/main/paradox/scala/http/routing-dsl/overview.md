# Routing DSL Overview

The Akka HTTP @ref[Low-Level Server-Side API](../low-level-server-side-api.md#http-low-level-server-side-api) provides a `Flow`- or `Function`-level interface that allows
an application to respond to incoming HTTP requests by simply mapping requests to responses:

@@snip [HttpServerExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #low-level-server-example }

While it'd be perfectly possible to define a complete REST API service purely by pattern-matching against the incoming
`HttpRequest` (maybe with the help of a few extractors in the way of [Unfiltered](http://unfiltered.databinder.net/)) this approach becomes somewhat
unwieldy for larger services due to the amount of syntax "ceremony" required. Also, it doesn't help in keeping your
service definition as [DRY](http://en.wikipedia.org/wiki/Don%27t_repeat_yourself) as you might like.

As an alternative Akka HTTP provides a flexible DSL for expressing your service behavior as a structure of
composable elements (called @ref[Directives](directives/index.md#directives)) in a concise and readable way. Directives are assembled into a so called
*route structure* which, at its top-level, forms a handler `Flow` (or, alternatively, an async handler function) that
can be directly supplied to a `bind` call. The conversion from `Route` to flow can either be invoked explicitly
using `Route.handlerFlow` or, otherwise, the conversion is also provided implicitly by
`RouteResult.route2HandlerFlow` <a id="^1" href="#1">[1]</a>.

For example, the service definition from above, written using the routing DSL, would look like this:

@@snip [HttpServerExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #high-level-server-example }

The core of the Routing DSL becomes available with a single import:

```scala
import akka.http.scaladsl.server.Directives._
```

This example also relies on the pre-defined support for Scala XML with:

```scala
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
```

The very short example shown here is certainly not the best for illustrating the savings in "ceremony" and improvements
in conciseness and readability that the Routing DSL promises. The @ref[Long Example](index.md#long-example) might do a better job in this
regard.

For learning how to work with the Routing DSL you should first understand the concept of @ref[Routes](routes.md#routes).

> <a id="1" href="#^1">[1]</a> To be picked up automatically, the implicit conversion needs to be provided in the companion object of the source
type. However, as `Route` is just a type alias for `RequestContext => Future[RouteResult]`, there's no
companion object for `Route`. Fortunately, the [implicit scope](http://www.scala-lang.org/files/archive/spec/2.11/07-implicits.html#implicit-parameters) for finding an implicit conversion also
includes all types that are "associated with any part" of the source type which in this case means that the
implicit conversion will also be picked up from `RouteResult.route2HandlerFlow` automatically.