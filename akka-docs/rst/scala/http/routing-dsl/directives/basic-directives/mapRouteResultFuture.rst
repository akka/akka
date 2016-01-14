.. _-mapRouteResultFuture-:

mapRouteResultFuture
====================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapRouteResultFuture

Description
-----------

Asynchronous version of :ref:`-mapRouteResult-`.

It's similar to :ref:`-mapRouteResultWith-`, however it's ``Future[RouteResult] ⇒ Future[RouteResult]``
instead of ``RouteResult ⇒ Future[RouteResult]`` which may be useful when combining multiple transformantions
and / or wanting to ``recover`` from a failed route result.

See :ref:`Result Transformation Directives` for similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapRouteResultFuture
