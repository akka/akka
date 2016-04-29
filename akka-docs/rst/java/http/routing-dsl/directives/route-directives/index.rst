.. _RouteDirectives-java:

RouteDirectives
===============

The ``RouteDirectives`` have a special role in akka-http's routing DSL. Contrary to all other directives (except most
:ref:`FileAndResourceDirectives-java`) they do not produce instances of type ``Directive[L <: HList]`` but rather "plain"
routes of type ``Route``.
The reason is that the ``RouteDirectives`` are not meant for wrapping an inner route (like most other directives, as
intermediate-level elements of a route structure, do) but rather form the leaves of the actual route structure **leaves**.

So in most cases the inner-most element of a route structure branch is one of the ``RouteDirectives`` (or
:ref:`FileAndResourceDirectives-java`):

.. toctree::
   :maxdepth: 1

   complete
   failWith
   redirect
   reject
