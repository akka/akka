.. _Rejections:

Rejections
==========

In the chapter about constructing :ref:`Routes` the ``~`` operator was introduced, which connects two routes in a way
that allows a second route to get a go at a request if the first route "rejected" it. The concept of "rejections" is
used by Akka HTTP for maintaining a more functional overall architecture and in order to be able to properly
handle all kinds of error scenarios.

When a filtering directive, like the :ref:`-get-` directive, cannot let the request pass through to its inner Route because
the filter condition is not satisfied (e.g. because the incoming request is not a GET request) the directive doesn't
immediately complete the request with an error response. Doing so would make it impossible for other routes chained in
after the failing filter to get a chance to handle the request.
Rather, failing filters "reject" the request in the same way as by explicitly calling ``requestContext.reject(...)``.

After having been rejected by a route the request will continue to flow through the routing structure and possibly find
another route that can complete it. If there are more rejections all of them will be picked up and collected.

If the request cannot be completed by (a branch of) the route structure an enclosing :ref:`-handleRejections-` directive
can be used to convert a set of rejections into an ``HttpResponse`` (which, in most cases, will be an error response).
``Route.seal`` internally wraps its argument route with the :ref:`-handleRejections-` directive in order to "catch"
and handle any rejection.


Predefined Rejections
---------------------

A rejection encapsulates a specific reason why a Route was not able to handle a request. It is modeled as an object of
type ``Rejection``. Akka HTTP comes with a set of `predefined rejections`__, which are used by various
:ref:`predefined directives <Predefined Directives>`.

Rejections are gathered up over the course of a Route evaluation and finally converted to ``HttpResponse`` replies by
the :ref:`-handleRejections-` directive if there was no way for the request to be completed.

__ @github@/akka-http/src/main/scala/akka/http/server/Rejection.scala


.. _RejectionHandler:

RejectionHandler
----------------

The :ref:`-handleRejections-` directive delegates the actual job of converting a list of rejections to its argument, a
RejectionHandler__, which is defined like this::

    trait RejectionHandler extends PartialFunction[List[Rejection], Route]

__ @github@/akka-http/src/main/scala/akka/http/server/RejectionHandler.scala

Since a ``RejectionHandler`` is a partial function it can choose, which rejections it would like to handle and
which not. Unhandled rejections will simply continue to flow through the route structure. The top-most
``RejectionHandler`` applied by :ref:`runRoute` will handle *all* rejections that reach it.

So, if you'd like to customize the way certain rejections are handled simply bring a custom ``RejectionHandler`` into
implicit scope of :ref:`runRoute` or pass it to an explicit :ref:`-handleRejections-` directive that you
have put somewhere into your route structure.

Here is an example:

.. includecode2:: ../code/docs/http/server/RejectionHandlerExamplesSpec.scala
   :snippet: example-1


Rejection Cancellation
----------------------

As you can see from its definition above the ``RejectionHandler`` handles not single rejections but a whole list of
them. This is because some route structure produce several "reasons" why a request could not be handled.

Take this route structure for example:

.. includecode2:: ../code/docs/http/server/RejectionHandlerExamplesSpec.scala
   :snippet: example-2

For uncompressed POST requests this route structure could yield two rejections:

- a ``MethodRejection`` produced by the :ref:`-get-` directive (which rejected because the request is not a GET request)
- an ``UnsupportedRequestEncodingRejection`` produced by the :ref:`-decodeRequest-` directive (which only accepts
  gzip-compressed requests)

In reality the route even generates one more rejection, a ``TransformationRejection`` produced by the :ref:`-post-`
directive. It "cancels" all other potentially existing *MethodRejections*, since they are invalid after the
:ref:`-post-` directive allowed the request to pass (after all, the route structure *can* deal with POST requests).
These types of rejection cancellations are resolved *before* a ``RejectionHandler`` sees the rejection list.
So, for the example above the ``RejectionHandler`` will be presented with only a single-element rejection list,
containing nothing but the ``UnsupportedRequestEncodingRejection``.

.. _empty rejections:

Empty Rejections
----------------

Since rejections are passed around in lists you might ask yourself what the semantics of an empty rejection list are.
In fact, empty rejection lists have well defined semantics. They signal that a request was not handled because the
respective resource could not be found. Akka HTTP reserves the special status of "empty rejection" to this most
common failure a service is likely to produce.

So, for example, if the :ref:`-path-` directive rejects a request, it does so with an empty rejection list. The
:ref:`-host-` directive behaves in the same way.
