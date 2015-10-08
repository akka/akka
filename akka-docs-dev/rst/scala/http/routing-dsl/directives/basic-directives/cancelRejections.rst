.. _-cancelRejections-:

cancelRejections
================

Adds a ``TransformationRejection`` cancelling all matching rejections
to the rejections potentially coming back from the inner route

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: cancelRejections

Description
-----------

Cancels all rejections created by the inner route for which the condition argument function returns ``true``.

See also :ref:`-cancelRejection-`, for canceling a specific rejection.

Read :ref:`rejections-scala` to learn more about rejections.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: cancelRejections-filter-example
