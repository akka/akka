.. _-extractLog-:

extractLog
==========

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractLog

Description
-----------

Extracts a :class:`LoggingAdapter` from the request context which can be used for logging inside the route.

The ``extractLog`` directive is used for providing logging to routes, such that they don't have to depend on
closing over a logger provided in the class body.

See :ref:`-extract-` and :ref:`ProvideDirectives` for an overview of similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: 0extractLog
