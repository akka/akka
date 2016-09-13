.. _-mapRouteResultWith-java-:

mapRouteResultWith
==================

Description
-----------

Changes the message the inner route sends to the responder.

The ``mapRouteResult`` directive is used as a building block for :ref:`Custom Directives-java` to transform the
:class:`RouteResult` coming back from the inner route. It's similar to the :ref:`-mapRouteResult-java-` directive but
returning a ``CompletionStage`` instead of a result immediately, which may be useful for longer running transformations.

See :ref:`Result Transformation Directives-java` for similar directives.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#mapRouteResultWith
