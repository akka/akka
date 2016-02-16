.. _-mapRouteResultWithPF-:

mapRouteResultWithPF
====================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapRouteResultWithPF

Description
-----------

Asynchronous variant of :ref:`-mapRouteResultPF-`.

Changes the message the inner route sends to the responder.

The ``mapRouteResult`` directive is used as a building block for :ref:`Custom Directives` to transform the
:ref:`RouteResult` coming back from the inner route.

See :ref:`Result Transformation Directives` for similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapRouteResultWithPF-0
