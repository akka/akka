# Rejections

In the chapter about constructing @ref[Routes](routes.md) the @scala[`~` operator]@java[`RouteDirectives.route()` method] was introduced, which connects two or more routes in a way
that allows the next specified route to get a go at a request if the first route "rejected" it. The concept of "rejections" is
used by Akka HTTP for maintaining a more functional overall architecture and in order to be able to properly
handle all kinds of error scenarios.

When a filtering directive, like the @ref[get](directives/method-directives/get.md) directive, cannot let the request pass through to its inner route because
the filter condition is not satisfied (e.g. because the incoming request is not a GET request) the directive doesn't
immediately complete the request with an error response. Doing so would make it impossible for other routes chained in
after the failing filter to get a chance to handle the request.
Rather, failing filters "reject" the request in the same way as by explicitly calling `requestContext.reject(...)`.

After having been rejected by a route the request will continue to flow through the routing structure and possibly find
another route that can complete it. If there are more rejections all of them will be picked up and collected.

If the request cannot be completed by (a branch of) the route structure an enclosing @ref[handleRejections](directives/execution-directives/handleRejections.md) directive
can be used to convert a set of rejections into an @unidoc[HttpResponse] (which, in most cases, will be an error response).
`Route.seal()` internally wraps its argument route with the @ref[handleRejections](directives/execution-directives/handleRejections.md) directive in order to "catch"
and handle any rejection.

## Predefined Rejections

A rejection encapsulates a specific reason why a route was not able to handle a request. It is modeled as an object of
type @unidoc[Rejection]. Akka HTTP comes with a set of @scala[@scaladoc[predefined rejections](akka.http.scaladsl.server.Rejection)]@java[@javadoc[predefined rejections](akka.http.javadsl.server.Rejections)], which are used by the many
@ref[predefined directives](directives/alphabetically.md).

Rejections are gathered up over the course of a Route evaluation and finally converted to @unidoc[HttpResponse] replies by
the @ref[handleRejections](directives/execution-directives/handleRejections.md) directive if there was no way for the request to be completed.

<a id="the-rejectionhandler"></a>
## The RejectionHandler

The @ref[handleRejections](directives/execution-directives/handleRejections.md) directive delegates the actual job of converting a list of rejections to the provided @scala[@scaladoc[RejectionHandler](akka.http.scaladsl.server.RejectionHandler)]@java[@javadoc[RejectionHandler](akka.http.javadsl.server.RejectionHandler)],
so it can choose whether it would like to handle the current set of rejections or not.
Unhandled rejections will simply continue to flow through the route structure.

The default `RejectionHandler` applied by the top-level glue code that turns a @unidoc[Route] into a
@unidoc[Flow] or async handler function for the @ref[low-level API](../server-side/low-level-api.md)
@scala[(via `Route.handlerFlow` or `Route.asyncHandler`)]
will handle *all* rejections that reach it.

## Rejection Cancellation

As mentioned above, the `RejectionHandler` doesn't handle single rejections but a whole list of
them. This is because some route structure produce several "reasons" why a request could not be handled.

Take this route structure for example:

Scala
:  @@snip [RejectionHandlerExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/RejectionHandlerExamplesSpec.scala) { #example-1 }

Java
:  @@snip [RejectionHandlerExamplesTest.java]($test$/java/docs/http/javadsl/server/RejectionHandlerExamplesTest.java) { #example1 }

For uncompressed POST requests this route structure would initially yield two rejections:

 * a @unidoc[MethodRejection] produced by the @ref[get](directives/method-directives/get.md) directive (which rejected because the request is not a GET request)
 * an @unidoc[UnsupportedRequestEncodingRejection] produced by the @ref[decodeRequestWith](directives/coding-directives/decodeRequestWith.md) directive (which only accepts
gzip-compressed requests here)

In reality the route even generates one more rejection, a @unidoc[TransformationRejection] produced by the @ref[post](directives/method-directives/post.md)
directive. It "cancels" all other potentially existing *MethodRejections*, since they are invalid after the
@ref[post](directives/method-directives/post.md) directive allowed the request to pass (after all, the route structure *can* deal with POST requests).
These types of rejection cancellations are resolved *before* a `RejectionHandler` is called with any rejection.
So, for the example above the `RejectionHandler` will be presented with only one single rejection, the @unidoc[UnsupportedRequestEncodingRejection].

<a id="empty-rejections"></a>
## Empty Rejections

Internally rejections stored in an immutable list, so you might ask yourself what the semantics of
an empty rejection list are. In fact, empty rejection lists have well defined semantics. They signal that a request was
not handled because the respective resource could not be found. Akka HTTP reserves the special status of "empty
rejection" to this most common failure a service is likely to produce.

So, for example, if the @ref[path](directives/path-directives/path.md) directive rejects a request it does so with an empty rejection list. The
@ref[host](directives/host-directives/host.md) directive behaves in the same way.

## Customizing Rejection Handling

If you'd like to customize the way certain rejections are handled you'll have to write a custom
[RejectionHandler](#the-rejectionhandler). Here is an example:

Scala
:  @@snip [RejectionHandlerExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/RejectionHandlerExamplesSpec.scala) { #custom-handler-example }

Java
:  @@snip [RejectionHandlerExamplesTest.java]($test$/java/docs/http/javadsl/server/RejectionHandlerExamplesTest.java) { #custom-handler-example-java }

The easiest way to construct a `RejectionHandler` is with `RejectionHandler.newBuilder()` that Akka HTTP provides.
After having created a new `Builder` instance
you can attach handling logic for certain types of rejections through three helper methods:

@scala[handle(PartialFunction[Rejection, Route])]@java[handle(Class<T>, Function<T, Route>)]
: Handles the provided type of rejections with the given function. The provided function simply produces a @unidoc[Route] which is
run when the rejection is "caught". This makes the full power of the Routing DSL available for defining rejection
handlers and even allows for recursing back into the main route structure if required.

@scala[handleAll[T <: Rejection: ClassTag](f: immutable.Seq[T] ⇒ Route)]@java[handleAll<T extends Rejection>(Class<T>, Function<List<T>, Route>)]
: Handles all rejections of a certain type at the same time. This is useful for cases where your need access to more
than the first rejection of a certain type, e.g. for producing the error message to an unsupported request method.

handleNotFound(Route)
: As described [above](#empty-rejections) "Resource Not Found" is special as it is represented with an empty
rejection set. The `handleNotFound` helper let's you specify the "recovery route" for this case.


Even though you could handle several different rejection types in a single partial function supplied to `handle`@java[ by "listening" to the `Rejection.class`],
it is recommended to split these up into distinct `handle` attachments instead.
This way the priority between rejections is properly defined via the order of your `handle` clauses rather than the
(sometimes hard to predict or control) order of rejections in the rejection set.

Once you have defined your custom `RejectionHandler` you have two options for "activating" it:

 1. @scala[Bring it into implicit scope at the top-level]@java[Pass it to the `seal()` method of the @unidoc[Route] class]
 2. Supply it as an argument to the @ref[handleRejections](directives/execution-directives/handleRejections.md) directive

In the first case your handler will be "sealed" (which means that it will receive the default handler as a fallback for
all cases your handler doesn't handle itself) and used for all rejections that are not handled within the route structure
itself.

The second case allows you to restrict the applicability of your handler to certain branches of your route structure.

### Customising rejection HTTP Responses

It is also possible to customise just the responses that are returned by a defined rejection handler.
This can be useful for example if you like the rejection messages and status codes of the default handler,
however you'd like to wrap those responses in JSON or some other content type.

Please note that since those are not 200 responses, a different content type than the one that was sent in
a client's @unidoc[Accept] header *is* legal. Thus the default handler renders such rejections as `text/plain`.

In order to customise the HTTP Responses of an existing handler you can call the
`mapRejectionResponse` method on such handler as shown in the example below:

Scala
:  @@snip [RejectionHandlerExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/RejectionHandlerExamplesSpec.scala) { #example-json }

Java
:  @@snip [RejectionHandlerExamplesTest.java]($test$/java/docs/http/javadsl/server/RejectionHandlerExamplesTest.java) { #example-json }

#### Adding the unmatched route in handleNotFound

Since rejection handlers are routes themselves, it is possible to do anything you could possibly want inside such handler.
For example you may want to include the path which was not found in the response to the client, this is as simple as
using the `extractUnmatchedPath` and completing the route with it.

Scala
:  @@snip [RejectionHandlerExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/RejectionHandlerExamplesSpec.scala) { #not-found-with-path }

Java
:  @@snip [ExecutionDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/ExecutionDirectivesExamplesTest.java) { #handleNotFoundWithDefails }

If you want to add even more information you can obtain the full request by using `extractRequest` as well.
