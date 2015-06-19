.. _-validate-:

validate
========

Checks an arbitrary condition and passes control to the inner route if it returns ``true``. Otherwise, rejects the
request with a ``ValidationRejection`` containing the given error message.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala
   :snippet: validate


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: validate-example
