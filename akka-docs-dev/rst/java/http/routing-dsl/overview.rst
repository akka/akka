.. _routing-java:

Routing DSL Overview
====================

The Akka HTTP :ref:`http-low-level-server-side-api-java` provides a ``Flow``- or ``Function``-level interface that allows
an application to respond to incoming HTTP requests by simply mapping requests to responses
(excerpt from :ref:`Low-level server side example <http-low-level-server-side-example-java>`):

.. includecode:: ../../code/docs/http/javadsl/server/HttpServerExampleDocTest.java
  :include: request-handler

While it'd be perfectly possible to define a complete REST API service purely by inspecting the incoming
``HttpRequest`` this approach becomes somewhat unwieldy for larger services due to the amount of syntax "ceremony"
required. Also, it doesn't help in keeping your service definition as DRY_ as you might like.

As an alternative Akka HTTP provides a flexible DSL for expressing your service behavior as a structure of
composable elements (called :ref:`directives-java`) in a concise and readable way. Directives are assembled into a so called
*route structure* which, at its top-level, can be used to create a handler ``Flow`` (or, alternatively, an
async handler function) that can be directly supplied to a ``bind`` call.

Here's the complete example rewritten using the composable high-level API:

.. includecode:: ../../code/docs/http/javadsl/server/HighLevelServerExample.java
  :include: high-level-server-example

Heart of the high-level architecture is the route tree. It is a big expression of type ``Route``
that is evaluated only once during startup time of your service. It completely describes how your service
should react to any request.

The type ``Route`` is the basic building block of the route tree. It defines if and a how a request should
be handled. Routes are composed to form the route tree in the following two ways.

A route can be wrapped by a "Directive" which adds some behavioral aspect to its wrapped "inner route". ``path("ping")`` is such
a directive that implements a path filter, i.e. it only passes control to its inner route when the unmatched path
matches ``"ping"``. Directives can be more versatile than this: A directive can also transform the request before
passing it into its inner route or transform a response that comes out of its inner route. It's a general and powerful
abstraction that allows to package any kind of HTTP processing into well-defined blocks that can be freely combined.
akka-http defines a library of predefined directives and routes for all the various aspects of dealing with
HTTP requests and responses.

Read more about :ref:`directives-java`.

The other way of composition is defining a list of ``Route`` alternatives. Alternative routes are tried one after
the other until one route "accepts" the request and provides a response. Otherwise, a route can also "reject" a request,
in which case further alternatives are explored. Alternatives are specified by passing a list of routes either
to ``Directive.route()`` as in ``pathSingleSlash().route()`` or to directives that directly take a variable number
of inner routes as argument like ``get()`` here.

Read more about :ref:`routes-java`.

Another important building block is a ``RequestVal<T>``. It represents a value that can be extracted from a
request (like the URI parameter ``Parameters.stringValue("name")`` in the example) and which is then interpreted
as a value of type ``T``. Examples of HTTP aspects represented by a ``RequestVal`` are URI parameters, HTTP form
fields, details of the request like headers, URI, the entity, or authentication data.

Read more about :ref:`request-vals-java`.

The actual application-defined processing of a request is defined with a ``Handler`` instance or by specifying
a handling method with reflection. A handler can receive the value of any request values and is converted into
a ``Route`` by using one of the ``BasicDirectives.handleWith`` directives.

Read more about :ref:`handlers-java`.

Requests or responses often contain data that needs to be interpreted or rendered in some way.
Akka-http provides the abstraction of ``Marshaller`` and ``Unmarshaller`` that define how domain model objects map
to HTTP entities.

Read more about :ref:`marshalling-java`.

akka-http contains a testkit that simplifies testing routes. It allows to run test-requests against (sub-)routes
quickly without running them over the network and helps with writing assertions on HTTP response properties.

Read more about :ref:`http-testkit-java`.

.. _DRY: http://en.wikipedia.org/wiki/Don%27t_repeat_yourself

.. _handling-http-server-failures-high-level-java:

Handling HTTP Server failures in the High-Level API
---------------------------------------------------
There are various situations when failure may occur while initialising or running an Akka HTTP server.
Akka by default will log all these failures, however sometimes one may want to react to failures in addition
to them just being logged, for example by shutting down the actor system, or notifying some external monitoring
end-point explicitly.

Bind failures
^^^^^^^^^^^^^
For example the server might be unable to bind to the given port. For example when the port
is already taken by another application, or if the port is privileged (i.e. only usable by ``root``).
In this case the "binding future" will fail immediatly, and we can react to if by listening on the Future's completion:

.. includecode:: ../../code/docs/http/javadsl/server/HighLevelServerBindFailureExample.java
  :include: binding-failure-high-level-example


.. note::
  For a more low-level overview of the kinds of failures that can happen and also more fine-grained control over them
  refer to the :ref:`handling-http-server-failures-low-level-java` documentation.

