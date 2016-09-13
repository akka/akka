.. _-mapRouteResultPF-java-:

mapRouteResultPF
================

Description
-----------
*Partial Function* version of :ref:`-mapRouteResult-java-`.

Changes the message the inner route sends to the responder.

The ``mapRouteResult`` directive is used as a building block for :ref:`Custom Directives-java` to transform the
:class:`RouteResult` coming back from the inner route. It's similar to the :ref:`-mapRouteResult-java-` directive but allows to
specify a partial function that doesn't have to handle all potential ``RouteResult`` instances.

See :ref:`Result Transformation Directives-java` for similar directives.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#mapRouteResultPF

