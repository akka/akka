Routing DSL Overview
====================

The Akka HTTP :ref:`http-low-level-server-side-api` provides a ``Flow``- or ``Function``-level interface that allows
an application to respond to incoming HTTP requests by simply mapping requests to responses:

.. includecode2:: ../../code/docs/http/scaladsl/HttpServerExampleSpec.scala
   :snippet: low-level-server-example

While it'd be perfectly possible to define a complete REST API service purely by pattern-matching against the incoming
``HttpRequest`` (maybe with the help of a few extractors in the way of `Unfiltered`_) this approach becomes somewhat
unwieldy for larger services due to the amount of syntax "ceremony" required. Also, it doesn't help in keeping your
service definition as DRY_ as you might like.

As an alternative Akka HTTP provides a flexible DSL for expressing your service behavior as a structure of
composable elements (called :ref:`Directives`) in a concise and readable way. Directives are assembled into a so called
*route structure* which, at its top-level, forms a handler ``Flow`` (or, alternatively, an async handler function) that
can be directly supplied to a ``bind`` call.

For example, the service definition from above, written using the routing DSL, would look like this:

.. includecode2:: ../../code/docs/http/scaladsl/HttpServerExampleSpec.scala
   :snippet: high-level-server-example

The core of the Routing DSL becomes available with a single import::

    import akka.http.scaladsl.server.Directives._

This example also relies on the pre-defined support for Scala XML with::

    import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._

The very short example shown here is certainly not the best for illustrating the savings in "ceremony" and improvements
in conciseness and readability that the Routing DSL promises. The :ref:`Long Example` might do a better job in this
regard.

For learning how to work with the Routing DSL you should first understand the concept of :ref:`Routes`.


.. _Unfiltered: http://unfiltered.databinder.net/
.. _DRY: http://en.wikipedia.org/wiki/Don%27t_repeat_yourself