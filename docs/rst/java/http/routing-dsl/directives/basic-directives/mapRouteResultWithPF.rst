.. _-mapRouteResultWithPF-java-:

mapRouteResultWithPF
====================

Description
-----------

Asynchronous variant of :ref:`-mapRouteResultPF-java-`.

Changes the message the inner route sends to the responder.

The ``mapRouteResult`` directive is used as a building block for :ref:`Custom Directives-java` to transform the
:class:`RouteResult` coming back from the inner route.

See :ref:`Result Transformation Directives-java` for similar directives.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#mapRouteResultWithPF
