.. _-cancelRejection-:

cancelRejection
===============

Adds a ``TransformationRejection`` cancelling all rejections equal to the
given one to the rejections potentially coming back from the inner route.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: cancelRejection

Description
-----------

Cancels a rejection which may potentially come back from the inner route.

Read :ref:`rejections-scala` to learn more about rejections.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: cancelRejection-example
