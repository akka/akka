.. _-mapRouteResultFuture-java-:

mapRouteResultFuture
====================

Description
-----------

Asynchronous version of :ref:`-mapRouteResult-java-`.

It's similar to :ref:`-mapRouteResultWith-java-`, however it's
``Function<CompletionStage<RouteResult>, CompletionStage<RouteResult>>``
instead of ``Function<RouteResult, CompletionStage<RouteResult>>`` which may be useful when
combining multiple transformations and / or wanting to ``recover`` from a failed route result.

See :ref:`Result Transformation Directives-java` for similar directives.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#mapRouteResultFuture
