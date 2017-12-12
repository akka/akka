<a id="routes"></a>
# Routes

The "Route" is the central concept of Akka HTTP's Routing DSL. All the structures you build with the DSL, no matter
whether they consists of a single line or span several hundred lines, are @scala[`type`]@java[`function`] turning a 
@unidoc[RequestContext] into a @scala[`Future[RouteResult]`]@java[`CompletionStage<RouteResult>`].

@@@ div { .group-scala }

```scala
type Route = RequestContext => Future[RouteResult]
```
It's a simple alias for a function turning a @unidoc[RequestContext] into a `Future[RouteResult]`.

@@@

@@@ div { .group-java }

A @unidoc[Route] itself is a function that operates on a @unidoc[RequestContext] and returns a @unidoc[RouteResult]. The
@unidoc[RequestContext] is a data structure that contains the current request and auxiliary data like the so far unmatched
path of the request URI that gets passed through the route structure. It also contains the current `ExecutionContext`
and `akka.stream.Materializer`, so that these don't have to be passed around manually.

@@@

Generally when a route receives a request (or rather a @unidoc[RequestContext] for it) it can do one of these things:

 * Complete the request by returning the value of `requestContext.complete(...)`
 * Reject the request by returning the value of `requestContext.reject(...)` (see @ref[Rejections](rejections.md#rejections))
 * Fail the request by returning the value of `requestContext.fail(...)` or by just throwing an exception (see @ref[Exception Handling](exception-handling.md#exception-handling))
 * Do any kind of asynchronous processing and instantly return a @scala[`Future[RouteResult]`]@java[`CompletionStage<RouteResult>`] to be eventually completed later

The first case is pretty clear, by calling `complete` a given response is sent to the client as reaction to the
request. In the second case "reject" means that the route does not want to handle the request. You'll see further down
in the section about route composition what this is good for.

A @unidoc[Route] can be "sealed" using `Route.seal`, which relies on the in-scope `RejectionHandler` and @unidoc[ExceptionHandler]
instances to convert rejections and exceptions into appropriate HTTP responses for the client.
@ref[Sealing a Route](#sealing-a-route) is described more in detail later. 


Using `Route.handlerFlow` or `Route.asyncHandler` a @unidoc[Route] can be lifted into a handler @unidoc[Flow] or async handler
function to be used with a `bindAndHandleXXX` call from the @ref[Low-Level Server-Side API](../server-side/low-level-api.md).

Note: There is also an implicit conversion from @unidoc[Route] to @unidoc[Flow[HttpRequest, HttpResponse, Unit]] defined in the
@unidoc[RouteResult] companion, which relies on `Route.handlerFlow`.

<a id="requestcontext"></a>
## RequestContext

The request context wraps an @unidoc[HttpRequest] instance to enrich it with additional information that are typically
required by the routing logic, like an `ExecutionContext`, @unidoc[Materializer], @unidoc[LoggingAdapter] and the configured
@unidoc[RoutingSettings]. It also contains the `unmatchedPath`, a value that describes how much of the request URI has not
yet been matched by a @ref[Path Directive](directives/path-directives/index.md#pathdirectives).

The @unidoc[RequestContext] itself is immutable but contains several helper methods which allow for convenient creation of
modified copies.

<a id="routeresult"></a>
## RouteResult

@unidoc[RouteResult] is a simple abstract data type (ADT) that models the possible non-error results of a @unidoc[Route].
It is defined as such:

@@@ div { .group-scala }

```scala
sealed trait RouteResult

object RouteResult {
  final case class Complete(response: HttpResponse) extends RouteResult
  final case class Rejected(rejections: immutable.Seq[Rejection]) extends RouteResult
}
```

@@@

Usually you don't create any @unidoc[RouteResult] instances yourself, but rather rely on the pre-defined @ref[RouteDirectives](directives/route-directives/index.md#routedirectives)
(like @ref[complete](directives/route-directives/complete.md#complete), @ref[reject](directives/route-directives/reject.md#reject) or @ref[redirect](directives/route-directives/redirect.md#redirect)) or the respective methods on the [RequestContext](#requestcontext)
instead.

## Composing Routes

There are three basic operations we need for building more complex routes from simpler ones:

 * Route transformation, which delegates processing to another, "inner" route but in the process changes some properties
of either the incoming request, the outgoing response or both
 * Route filtering, which only lets requests satisfying a given filter condition pass and rejects all others
 * Route chaining, which tries a second route if a given first one was rejected

The last point is achieved with the concatenation operator `~`, which is an extension method that becomes available
when you `import akka.http.scaladsl.server.Directives._`.
The first two points are provided by so-called @ref[Directives](directives/index.md#directives) of which a large number is already predefined by Akka
HTTP and which you can also easily create yourself.
@ref[Directives](directives/index.md#directives) deliver most of Akka HTTP's power and flexibility.

<a id="the-routing-tree"></a>
## The Routing Tree

Essentially, when you combine directives and custom routes via nesting and the @scala[`~` operator]@java[alternative], you build a routing
structure that forms a tree. When a request comes in it is injected into this tree at the root and flows down through
all the branches in a depth-first manner until either some node completes it or it is fully rejected.

Consider this schematic example:

@@@ div { .group-scala }

```scala
val route =
  a {
    b {
      c {
        ... // route 1
      } ~
      d {
        ... // route 2
      } ~
      ... // route 3
    } ~
    e {
      ... // route 4
    }
  }
```

@@@

@@@ div { .group-java }

```java
import static akka.http.javadsl.server.Directives.*;

Route route =
  directiveA(route(() ->
    directiveB(route(() ->
      directiveC(
        ... // route 1
      ),
      directiveD(
        ... // route 2
      ),
      ... // route 3
    )),
    directiveE(
      ... // route 4
    )
  ));
```

@@@

Here five directives form a routing tree.

 * Route 1 will only be reached if directives `a`, `b` and `c` all let the request pass through.
 * Route 2 will run if `a` and `b` pass, `c` rejects and `d` passes.
 * Route 3 will run if `a` and `b` pass, but `c` and `d` reject.

Route 3 can therefore be seen as a "catch-all" route that only kicks in, if routes chained into preceding positions
reject. This mechanism can make complex filtering logic quite easy to implement: simply put the most
specific cases up front and the most general cases in the back.

## Sealing a Route

As described in @ref[Rejections](rejections.md#rejections-scala) and @ref[Exception Handling](exception-handling.md#exception-handling-scala),
there are generally two ways to handle rejections and exceptions.

 * Bring rejection/exception handlers @scala[`into implicit scope at the top-level`]@java[`seal()` method of the @unidoc[Route]]
 * Supply handlers as arguments to @ref[handleRejections](directives/execution-directives/handleRejections.md#handlerejections) and @ref[handleExceptions](directives/execution-directives/handleExceptions.md#handleexceptions) directives 

In the first case your handlers will be "sealed", (which means that it will receive the default handler as a fallback for all cases your handler doesn't handle itself) 
and used for all rejections/exceptions that are not handled within the route structure itself.

### Route.seal() method to modify HttpResponse

In application code, unlike @ref[test code](testkit.md#testing-sealed-routes), you don't need to use the `Route.seal()` method to seal a route.
As long as you bring implicit rejection and/or exception handlers to the top-level scope, your route is sealed. 

However, you can use `Route.seal()` to perform modification on HttpResponse from the route.
For example, if you want to add a special header, but still use the default rejection handler, then you can do the following.
In the below case, the special header is added to rejected responses which did not match the route, as well as successful responses which matched the route.

Scala
:   @@snip [RouteSealExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/RouteSealExampleSpec.scala) { #route-seal-example }

Java
:   @@snip [RouteSealExample.java](../../../../../test/java/docs/http/javadsl/RouteSealExample.java) { #route-seal-example }
