.. _-reject-:

reject
======

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala
   :snippet: reject


Description
-----------
Explicitly rejects the request optionally using the given rejection(s).

``reject`` uses the given rejection instances (which might be the empty ``Seq``) to construct a ``Route`` which simply
calls ``requestContext.reject``. See the chapter on :ref:`rejections-scala` for more information on what this means.

After the request has been rejected at the respective point it will continue to flow through the routing structure in
the search for a route that is able to complete it.

The explicit ``reject`` directive is used mostly when building :ref:`Custom Directives`, e.g. inside of a ``flatMap``
modifier for "filtering out" certain cases.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/RouteDirectivesExamplesSpec.scala
   :snippet: reject-examples