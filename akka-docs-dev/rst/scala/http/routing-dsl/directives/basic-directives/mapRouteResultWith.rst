.. _-mapRouteResultWith-:

mapRouteResultWith
==================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapRouteResultWith

Description
-----------

Changes the message the inner route sends to the responder.

The ``mapRouteResult`` directive is used as a building block for :ref:`Custom Directives` to transform the
:ref:`RouteResult` coming back from the inner route. It's similar to the :ref:`-mapRouteResult-` directive but
returning a ``Future`` instead of a result immediadly, which may be useful for longer running transformations.

See :ref:`Result Transformation Directives` for similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapRouteResultWith-0
