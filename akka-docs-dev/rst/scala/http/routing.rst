.. _http-routing-scala:

The Routing DSL
===============

.. _Routes:

Routes
------

The "Route" is the central concept of the routing DSL since all structures you can build with it are instances of
a ``Route``. The type Route is defined like this::

  type Route = RequestContext => Future[RouteResult]

It's a simple alias for a function taking a ``RequestContext`` as parameter and returning a ``Future[RouteResult]``.

Generally when a route receives a request (or rather a ``RequestContext`` for it) it can do one of these things:

- Complete the request by returning the value of  ``requestContext.complete(...)``
- Reject the request by returning the value of ``requestContext.reject(...)`` (see :ref:`Rejections`)
- Fail the request by returning the value of ``requestContext.fail(...)`` or by just throwing an exception (see :ref:`Exception Handling`)
- Do any kind of asynchronous processing and instantly return a ``Future[RouteResult]`` to be eventually completed later on

The first case is pretty clear, by calling ``complete`` a given response is sent to the client as reaction to the
request. In the second case "reject" means that the route does not want to handle the request. You'll see further down
in the section about route composition what this is good for.

A ``Route`` can be sealed using ``Route.seal`` and by supplying a ``RejectionHandler`` and an ``ExceptionHandler`` which
converts rejection results and exceptional results into appropriate HTTP responses for the peer.

A ``Route`` can be lifted into a handler to be used with the http-core API using ``Route.handlerFlow`` or
``Route.asyncHandler``.

.. _RequestContext:

RequestContext
--------------

The request context wraps a request together with additional contextual information to be passed through the route tree.
Also, it provides the only way of creating a ``RouteResult`` by calling one of the above methods.

In addition to the request it contains the ``unmatchedPath``, a value that describes how much of the request URI has not
yet been matched and instances of several configuration instances like a ``LoggingAdapter``, an ``ExecutionContext``, and
``RoutingSettings``, so that they don't have to be passed around explicitly.

The ``RequestContext`` itself is immutable but contains several helper methods to create updated versions.

Composing Routes
----------------

There are three basic operations we need for building more complex routes from simpler ones:

.. rst-class:: wide

- Route transformation, which delegates processing to another, "inner" route but in the process changes some properties
  of either the incoming request, the outgoing response or both
- Route filtering, which only lets requests satisfying a given filter condition pass and rejects all others
- Route chaining, which tries a second route if a given first one was rejected

The last point is achieved with the concatenation operator ``~``, which is an extension method provided by an implicit in
``RouteConcatenation``. The first two points are provided by so-called :ref:`Directives`, of which a large number is
already predefined by Akka HTTP and which you can also easily create yourself.
:ref:`Directives` deliver most of Akka HTTP's power and flexibility.

.. _The Routing Tree:

The Routing Tree
----------------

Essentially, when you combine directives and custom routes via nesting and the ``~`` operator, you build a routing
structure that forms a tree. When a request comes in it is injected into this tree at the root and flows down through
all the branches in a depth-first manner until either some node completes it or it is fully rejected.

Consider this schematic example::

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

Here five directives form a routing tree.

.. rst-class:: wide

- Route 1 will only be reached if directives ``a``, ``b`` and ``c`` all let the request pass through.
- Route 2 will run if ``a`` and ``b`` pass, ``c`` rejects and ``d`` passes.
- Route 3 will run if ``a`` and ``b`` pass, but ``c`` and ``d`` reject.

Route 3 can therefore be seen as a "catch-all" route that only kicks in, if routes chained into preceding positions
reject. This mechanism can make complex filtering logic quite easy to implement: simply put the most
specific cases up front and the most general cases in the back.


