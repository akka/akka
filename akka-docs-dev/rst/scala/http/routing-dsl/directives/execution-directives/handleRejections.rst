.. _-handleRejections-:

handleRejections
================

Handles rejections produced by the inner route and handles them using the specified ``RejectionHandler``.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/ExecutionDirectives.scala
   :snippet: handleRejections

Description
-----------

Using this directive is an alternative to using a global implicitly defined ``RejectionHandler`` that
applies to the complete route.

See :ref:`Rejections` for general information about options for handling rejections.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ExecutionDirectivesExamplesSpec.scala
   :snippet: handleRejections
