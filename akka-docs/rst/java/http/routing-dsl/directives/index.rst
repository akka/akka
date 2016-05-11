.. _directives-java:

Directives
==========

A "Directive" is a small building block used for creating arbitrarily complex :ref:`route structures <Routes>`.
Akka HTTP already pre-defines a large number of directives and you can easily construct your own:

.. toctree::
   :maxdepth: 1

   alphabetically
   by-trait
   custom-directives


Basics
------

:ref:`Routes-java` effectively are simply highly specialised functions that take a ``RequestContext`` and eventually ``complete`` it, 
which could (and often should) happen asynchronously.

With the :ref:`-complete-java-` directive this becomes even shorter::

    Route route = complete("yeah");

Writing multiple routes that are tried as alternatives (in-order of definition), is as simple as using the ``route(route1, route2)``,
method::

    Route routes = route(
      pathSingleSlash(() ->
        getFromResource("web/calculator.html")
      ),
      path("hello", () -> complete("World!))
    );


You could also simply define a "catch all" completion by providing it as the last route to attempt to match.
In the example below we use the ``get()`` (one of the :ref:`MethodDirectives-java`) to match all incoming ``GET``
requests for that route, and all other requests will be routed towards the other "catch all" route, that completes the route::

    Route route =
      get(
        () -> complete("Received GET")
      ).orElse(
        () -> complete("Received something else")
      )


If no route matches a given request, a default ``404 Not Found`` response will be returned as response.

Structure
---------

The general anatomy of a directive is as follows::

    directiveName(arguments [, ...], (extractions [, ...]) -> {
      ... // inner route
    })

It has a name, zero or more arguments and optionally an inner route (The :ref:`RouteDirectives-java` are special in that they
are always used at the leaf-level and as such cannot have inner routes).

Additionally directives can "extract" a number of values and make them available to their inner routes as function
arguments. When seen "from the outside" a directive with its inner route form an expression of type ``Route``.


What Directives do
------------------

A directive can do one or more of the following:

.. rst-class:: wide

* Transform the incoming ``RequestContext`` before passing it on to its inner route (i.e. modify the request)
* Filter the ``RequestContext`` according to some logic, i.e. only pass on certain requests and reject others
* Extract values from the ``RequestContext`` and make them available to its inner route as "extractions"
* Chain some logic into the :class:`RouteResult` future transformation chain (i.e. modify the response or rejection)
* Complete the request

This means a ``Directive`` completely wraps the functionality of its inner route and can apply arbitrarily complex
transformations, both (or either) on the request and on the response side.


Composing Directives
--------------------

TODO rewrite for Java API
