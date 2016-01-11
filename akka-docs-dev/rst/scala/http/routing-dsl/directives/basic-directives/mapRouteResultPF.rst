.. _-mapRouteResultPF-:

mapRouteResultPF
================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapRouteResultPF

Description
-----------
*Partial Function* version of :ref:`-mapRouteResult-`.

Changes the message the inner route sends to the responder.

The ``mapRouteResult`` directive is used as a building block for :ref:`Custom Directives` to transform the
:ref:`RouteResult` coming back from the inner route. It's similar to the :ref:`-mapRouteResult-` directive but allows to
specify a partial function that doesn't have to handle all potential ``RouteResult`` instances.

See :ref:`Result Transformation Directives` for similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapRouteResultPF
