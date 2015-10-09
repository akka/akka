.. _-handleRejections-:

handleRejections
================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ExecutionDirectives.scala
   :snippet: handleRejections

Description
-----------

Using this directive is an alternative to using a global implicitly defined ``RejectionHandler`` that
applies to the complete route.

See :ref:`rejections-scala` for general information about options for handling rejections.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ExecutionDirectivesExamplesSpec.scala
   :snippet: handleRejections
