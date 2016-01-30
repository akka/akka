.. _-cancelRejections-:

cancelRejections
================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: cancelRejections

Description
-----------

Adds a ``TransformationRejection`` cancelling all rejections created by the inner route for which
the condition argument function returns ``true``.

See also :ref:`-cancelRejection-`, for canceling a specific rejection.

Read :ref:`rejections-scala` to learn more about rejections.

For more advanced handling of rejections refer to the :ref:`-handleRejections-` directive
which provides a nicer DSL for building rejection handlers.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: cancelRejections-filter-example
