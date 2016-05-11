.. _rejections-java:

Rejections
==========
TODO update to Java APIs

In the chapter about constructing :ref:`Routes` the ``~`` operator was introduced, which connects two routes in a way
that allows a second route to get a go at a request if the first route "rejected" it. The concept of "rejections" is
used by Akka HTTP for maintaining a more functional overall architecture and in order to be able to properly
handle all kinds of error scenarios.

When a filtering directive, like the :ref:`-get-` directive, cannot let the request pass through to its inner route because
the filter condition is not satisfied (e.g. because the incoming request is not a GET request) the directive doesn't
immediately complete the request with an error response. Doing so would make it impossible for other routes chained in
after the failing filter to get a chance to handle the request.
Rather, failing filters "reject" the request in the same way as by explicitly calling ``requestContext.reject(...)``.

After having been rejected by a route the request will continue to flow through the routing structure and possibly find
another route that can complete it. If there are more rejections all of them will be picked up and collected.

If the request cannot be completed by (a branch of) the route structure an enclosing :ref:`-handleRejections-java-` directive
can be used to convert a set of rejections into an ``HttpResponse`` (which, in most cases, will be an error response).
``Route.seal`` internally wraps its argument route with the :ref:`-handleRejections-java-` directive in order to "catch"
and handle any rejection.


Predefined Rejections
---------------------

A rejection encapsulates a specific reason why a route was not able to handle a request. It is modeled as an object of
type ``Rejection``. Akka HTTP comes with a set of `predefined rejections`__, which are used by the many
:ref:`predefined directives <Predefined Directives-java>`.

Rejections are gathered up over the course of a Route evaluation and finally converted to ``HttpResponse`` replies by
the :ref:`-handleRejections-` directive if there was no way for the request to be completed.

__ @github@/akka-http/src/main/scala/akka/http/scaladsl/server/Rejection.scala


.. _The RejectionHandler-java:

The RejectionHandler
--------------------

The :ref:`-handleRejections-` directive delegates the actual job of converting a list of rejections to its argument, a
RejectionHandler__, which is defined like this::

    trait RejectionHandler extends (immutable.Seq[Rejection] ⇒ Option[Route])

__ @github@/akka-http/src/main/scala/akka/http/scaladsl/server/RejectionHandler.scala

Since a ``RejectionHandler`` returns an ``Option[Route]`` it can choose whether it would like to handle the current set
of rejections or not. If it returns ``None`` the rejections will simply continue to flow through the route structure.

The default ``RejectionHandler`` applied by the top-level glue code that turns a ``Route`` into a
``Flow`` or async handler function for the :ref:`low-level API <http-low-level-server-side-api>` (via
``Route.handlerFlow`` or ``Route.asyncHandler``) will handle *all* rejections that reach it.


Rejection Cancellation
----------------------

As you can see from its definition above the ``RejectionHandler`` doesn't handle single rejections but a whole list of
them. This is because some route structure produce several "reasons" why a request could not be handled.

Take this route structure for example:

TODO missing sample

For uncompressed POST requests this route structure would initially yield two rejections:

- a ``MethodRejection`` produced by the :ref:`-get-` directive (which rejected because the request is not a GET request)
- an ``UnsupportedRequestEncodingRejection`` produced by the :ref:`-decodeRequestWith-` directive (which only accepts
  gzip-compressed requests here)

In reality the route even generates one more rejection, a ``TransformationRejection`` produced by the :ref:`-post-`
directive. It "cancels" all other potentially existing *MethodRejections*, since they are invalid after the
:ref:`-post-` directive allowed the request to pass (after all, the route structure *can* deal with POST requests).
These types of rejection cancellations are resolved *before* a ``RejectionHandler`` sees the rejection list.
So, for the example above the ``RejectionHandler`` will be presented with only a single-element rejection list,
containing nothing but the ``UnsupportedRequestEncodingRejection``.


.. _Empty Rejections-java:

Empty Rejections
----------------

Since rejections are passed around in a list (or rather immutable ``Seq``) you might ask yourself what the semantics of
an empty rejection list are. In fact, empty rejection lists have well defined semantics. They signal that a request was
not handled because the respective resource could not be found. Akka HTTP reserves the special status of "empty
rejection" to this most common failure a service is likely to produce.

So, for example, if the :ref:`-path-` directive rejects a request it does so with an empty rejection list. The
:ref:`-host-` directive behaves in the same way.


Customizing Rejection Handling
------------------------------

If you'd like to customize the way certain rejections are handled you'll have to write a custom
:ref:`RejectionHandler <The RejectionHandler>`. Here is an example:

TODO missing sample

The easiest way to construct a ``RejectionHandler`` is via the ``RejectionHandler.Builder`` that Akka HTTP provides.
After having created a new ``Builder`` instance with ``RejectionHandler.newBuilder()``
you can attach handling logic for certain types of rejections through three helper methods:

handle
  Handles certain rejections with the given partial function. The partial function simply produces a ``Route`` which is
  run when the rejection is "caught". This makes the full power of the Routing DSL available for defining rejection
  handlers and even allows for recursing back into the main route structure if required.

handleAll[T <: Rejection]
  Handles all rejections of a certain type at the same time. This is useful for cases where your need access to more
  than the first rejection of a certain type, e.g. for producing the error message to an unsupported request method.

handleNotFound
  As described :ref:`above <Empty Rejections>` "Resource Not Found" is special as it is represented with an empty
  rejection set. The ``handleNotFound`` helper let's you specify the "recovery route" for this case.

Even though you could handle several different rejection types in a single partial function supplied to ``handle``
it is recommended to split these up into distinct ``handle`` attachments instead.
This way the priority between rejections is properly defined via the order of your ``handle`` clauses rather than the
(sometimes hard to predict or control) order of rejections in the rejection set.

Once you have defined your custom ``RejectionHandler`` you have two options for "activating" it:

1. Bring it into implicit scope at the top-level.
2. Supply it as argument to the :ref:`-handleRejections-` directive.

In the first case your handler will be "sealed" (which means that it will receive the default handler as a fallback for
all cases your handler doesn't handle itself) and used for all rejections that are not handled within the route structure
itself.

The second case allows you to restrict the applicability of your handler to certain branches of your route structure.
