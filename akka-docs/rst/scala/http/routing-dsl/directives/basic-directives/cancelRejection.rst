.. _-cancelRejection-:

cancelRejection
===============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: cancelRejection

Description
-----------

Adds a ``TransformationRejection`` cancelling all rejections equal to the
given one to the rejections potentially coming back from the inner route.

Read :ref:`rejections-scala` to learn more about rejections.

For more advanced handling of rejections refer to the :ref:`-handleRejections-` directive
which provides a nicer DSL for building rejection handlers.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: cancelRejection-example
