# Routing DSL Overview

The Akka HTTP @ref[Low-Level Server-Side API](../server-side/low-level-api.md) provides a @unidoc[Flow]- or `Function`-level interface that allows
an application to respond to incoming HTTP requests by simply mapping requests to responses
(excerpt from @ref[Low-level server side example](../server-side/low-level-api.md#http-low-level-server-side-example)):

Scala
:  @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #low-level-server-example }

Java
:  @@snip [HttpServerExampleDocTest.java]($test$/java/docs/http/javadsl/server/HttpServerExampleDocTest.java) { #request-handler }

While it'd be perfectly possible to define a complete REST API service purely by @scala[pattern-matching against]@java[inspecting] the incoming
@unidoc[HttpRequest] @scala[(maybe with the help of a few extractors in the way of [Unfiltered](http://unfiltered.databinder.net/))] this approach becomes somewhat
unwieldy for larger services due to the amount of syntax "ceremony" required. Also, it doesn't help in keeping your
service definition as [DRY](http://en.wikipedia.org/wiki/Don%27t_repeat_yourself) as you might like.

As an alternative Akka HTTP provides a flexible DSL for expressing your service behavior as a structure of
composable elements (called @ref[Directives](directives/index.md)) in a concise and readable way. Directives are assembled into a so called
*route structure* which, at its top-level, can be used to create a handler @unidoc[Flow] (or, alternatively, an async handler function) that
can be directly supplied to a `bind` call. @scala[The conversion from @unidoc[Route] to flow can either be invoked explicitly
using `Route.handlerFlow` or, otherwise, the conversion is also provided implicitly by
`RouteResult.route2HandlerFlow` <a id="^1" href="#1">[1]</a>.]

Here's the complete example rewritten using the composable high-level API:

Scala
:   @@snip [HttpServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpServerExampleSpec.scala) { #high-level-server-example }

Java
:   @@snip [HighLevelServerExample.java]($test$/java/docs/http/javadsl/server/HighLevelServerExample.java) { #high-level-server-example }

The core of the Routing DSL becomes available with a single import:

Scala
:   ```scala
import akka.http.scaladsl.server.Directives._
```

Java
:   ```java
import static akka.http.javadsl.server.Directives.*;
```

@@@ div { .group-java }

Or by extending the `akka.http.javadsl.server.AllDirectives` class which brings together all directives into a single class
for easier access:

```java
extends AllDirectives
```

Of course it is possible to directly import only the directives you need (i.e. @unidoc[WebSocketDirectives] etc).

@@@

@@@ div { .group-scala }

This example also relies on the pre-defined support for Scala XML with:

```scala
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
```

@@@

The very short example shown here is certainly not the best for illustrating the savings in "ceremony" and improvements
in conciseness and readability that the Routing DSL promises. The @ref[Long Example](index.md#long-example) might do a better job in this
regard.

For learning how to work with the Routing DSL you should first understand the concept of @ref[Routes](routes.md).

@@@ div { .group-scala }

> <a id="1" href="#^1">[1]</a> To be picked up automatically, the implicit conversion needs to be provided in the companion object of the source
type. However, as @unidoc[Route] is just a type alias for `RequestContext => Future[RouteResult]`, there's no
companion object for @unidoc[Route]. Fortunately, the [implicit scope](http://www.scala-lang.org/files/archive/spec/2.11/07-implicits.html#implicit-parameters) for finding an implicit conversion also
includes all types that are "associated with any part" of the source type which in this case means that the
implicit conversion will also be picked up from `RouteResult.route2HandlerFlow` automatically.

@@@
