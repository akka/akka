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
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
