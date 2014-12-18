.. _Directives:

Directives
==========

A "Directive" is a small building block to construct arbitrarily complex route structures.
Here is a simple example of a route built from directives:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-1

The general anatomy of a directive is as follows::

    name(arguments) { extractions =>
      ... // inner Route
    }

It has a name, zero or more arguments and optionally an inner Route. Additionally directives can "extract" a number of
values and make them available to their inner routes as function arguments. When seen "from the outside" a directive
with its inner Route form an expression of type ``Route``.

What Directives do
------------------

A directive can do one or more of the following:

.. rst-class:: wide

* Transform the incoming ``RequestContext`` before passing it on to its inner Route
* Filter the ``RequestContext`` according to some logic, i.e. only pass on certain requests and reject all others
* Extract values from the ``RequestContext`` and make them available to its inner Route as "extractions"
* Complete the request

The first point deserves some more discussion. The ``RequestContext`` is the central object that is passed on through a
route structure (also see :ref:`RequestContext`). When a directive (or better the Route it built) receives a ``RequestContext``
it can decide to pass this instance on unchanged to its inner Route or it can create a copy of the ``RequestContext`` instance,
with one or more changes, and pass on this copy to its inner Route. Typically this is good for two things:

* Transforming the ``HttpRequest`` instance
* "Hooking in" a response transformation function that changes the ``RouteResponse`` (and therefore the response).

This means a ``Directive`` completely wraps the functionality of its inner routes and can apply arbitrarily complex
transformations, both (or either) on the request and on the response side.

Composing Directives
--------------------

As you have seen from the examples presented so far the "normal" way of composing directives is nesting. Let's take
another look at the example from above:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-2

Here the ``get`` and ``put`` directives are chained together with the ``~`` operator to form a higher-level route that
serves as the inner Route of the ``path`` directive. To make this structure more explicit you could also write the whole
thing like this:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-3

What you can't see from this snippet is that directives are not implemented as simple methods but rather as stand-alone
objects of type ``Directive``. This gives you more flexibility when composing directives. For example you can
also use the ``|`` operator on directives. Here is yet another way to write the example:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-4

If you have a larger route structure where the ``(get | put)`` snippet appears several times you could also factor it
out like this:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-5

As an alternative to nesting you can also use the `&` operator:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-6

And once again, you can factor things out if you want:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-7

This type of combining directives with the ``|`` and ``&`` operators as well as "saving" more complex directive
configurations as a ``val`` works across the board, with all directives taking inner routes.

There is one more "ugly" thing remaining in our snippet: we have to fall back to the lowest-level route definition,
directly manipulating the ``RequestContext``, in order to get to the request method. It'd be nicer if we could somehow
"extract" the method name in a special directive, so that we can express our inner-most route with a simple
``complete``. As it turns out this is easy with the ``extract`` directive:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-8

Or differently:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-9

Now, pushing the "factoring out" of directive configurations to its extreme, we end up with this:

.. includecode2:: ../code/docs/http/server/DirectiveExamplesSpec.scala
   :snippet: example-A

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
