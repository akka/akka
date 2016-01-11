.. _-complete-:

complete
========

Signature
---------

::

    def complete[T :ToResponseMarshaller](value: T): StandardRoute
    def complete(response: HttpResponse): StandardRoute
    def complete(status: StatusCode): StandardRoute
    def complete[T :Marshaller](status: StatusCode, value: T): StandardRoute
    def complete[T :Marshaller](status: Int, value: T): StandardRoute
    def complete[T :Marshaller](status: StatusCode, headers: Seq[HttpHeader], value: T): StandardRoute
    def complete[T :Marshaller](status: Int, headers: Seq[HttpHeader], value: T): StandardRoute

The signature shown is simplified, the real signature uses magnets. [1]_

.. [1] See `The Magnet Pattern <http://spray.io/blog/2012-12-13-the-magnet-pattern/>`_ for an explanation of magnet-based overloading.


Description
-----------

Completes the request using the given argument(s).

``complete`` uses the given arguments to construct a ``Route`` which simply calls ``complete`` on the ``RequestContext``
with the respective ``HttpResponse`` instance.
Completing the request will send the response "back up" the route structure where all the logic runs that wrapping
directives have potentially chained into the :ref:`RouteResult` future transformation chain.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/RouteDirectivesExamplesSpec.scala
   :snippet: complete-examples