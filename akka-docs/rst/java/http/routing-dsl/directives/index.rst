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
In the example below we use the ``get()`` (one of the :ref:`method-directives-java`) to match all incoming ``GET``
requests for that route, and all other requests will be routed towards the other "catch all" route, that completes the route. 

    val route =
      get {
        complete("Received GET")
      } ~
      complete("Received something else")

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

.. note:: Gotcha: forgetting the ``~`` (tilde) character in between directives can often result in perfectly valid
  Scala code that compiles but lead to your composed directive only containing the up to where ``~`` is missing.

As you have seen from the examples presented so far the "normal" way of composing directives is nesting.
Let's take a look at this concrete example:

.. includecode2:: ../../../code/docs/http/scaladsl/server/DirectiveExamplesSpec.scala
   :snippet: example-1

Here the ``get`` and ``put`` directives are chained together with the ``~`` operator to form a higher-level route that
serves as the inner route of the ``path`` directive. To make this structure more explicit you could also write the whole
thing like this:

.. includecode2:: ../../../code/docs/http/scaladsl/server/DirectiveExamplesSpec.scala
   :snippet: example-2

What you can't see from this snippet is that directives are not implemented as simple methods but rather as stand-alone
objects of type ``Directive``. This gives you more flexibility when composing directives. For example you can
also use the ``|`` operator on directives. Here is yet another way to write the example:

.. includecode2:: ../../../code/docs/http/scaladsl/server/DirectiveExamplesSpec.scala
   :snippet: example-3

Or better (without dropping down to writing an explicit ``Route`` function manually):

.. includecode2:: ../../../code/docs/http/scaladsl/server/DirectiveExamplesSpec.scala
   :snippet: example-4

If you have a larger route structure where the ``(get | put)`` snippet appears several times you could also factor it
out like this:

.. includecode2:: ../../../code/docs/http/scaladsl/server/DirectiveExamplesSpec.scala
   :snippet: example-5

Note that, because ``getOrPut`` doesn't take any parameters, it can be a ``val`` here.

As an alternative to nesting you can also use the `&` operator:

.. includecode2:: ../../../code/docs/http/scaladsl/server/DirectiveExamplesSpec.scala
   :snippet: example-6

Here you can see that, when directives producing extractions are combined with ``&``, the resulting "super-directive"
simply extracts the concatenation of its sub-extractions.

And once again, you can factor things out if you want, thereby pushing the "factoring out" of directive configurations
to its extreme:

.. includecode2:: ../../../code/docs/http/scaladsl/server/DirectiveExamplesSpec.scala
   :snippet: example-7

This type of combining directives with the ``|`` and ``&`` operators as well as "saving" more complex directive
configurations as a ``val`` works across the board, with all directives taking inner routes.

Note that going this far with "compressing" several directives into a single one probably doesn't result in the most
readable and therefore maintainable routing code. It might even be that the very first of this series of examples
is in fact the most readable one.

Still, the purpose of the exercise presented here is to show you how flexible directives can be and how you can
use their power to define your web service behavior at the level of abstraction that is right for **your** application.


Type Safety of Directives
-------------------------

When you combine directives with the ``|`` and ``&`` operators the routing DSL makes sure that all extractions work as
expected and logical constraints are enforced at compile-time.

For example you cannot ``|`` a directive producing an extraction with one that doesn't::

    val route = path("order" / IntNumber) | get // doesn't compile

Also the number of extractions and their types have to match up::

    val route = path("order" / IntNumber) | path("order" / DoubleNumber)   // doesn't compile
    val route = path("order" / IntNumber) | parameter('order.as[Int])      // ok

When you combine directives producing extractions with the ``&`` operator all extractions will be properly gathered up::

    val order = path("order" / IntNumber) & parameters('oem, 'expired ?)
    val route =
      order { (orderId, oem, expired) =>
        ...
      }

Directives offer a great way of constructing your web service logic from small building blocks in a plug and play
fashion while maintaining DRYness and full type-safety. If the large range of :ref:`Predefined Directives` does not
fully satisfy your needs you can also very easily create :ref:`Custom Directives`.
