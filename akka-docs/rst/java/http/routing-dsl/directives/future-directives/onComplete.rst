.. _-onComplete-java-:

onComplete
==========

Description
-----------
Evaluates its parameter of type ``Future[T]``, and once the ``Future`` has been completed, extracts its
result as a value of type ``Try[T]`` and passes it to the inner route.

To handle the ``Failure`` case automatically and only work with the result value, use :ref:`-onSuccess-java-`.

To complete with a successful result automatically and just handle the failure result, use :ref:`-completeOrRecoverWith-java-`, instead.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala
   :snippet: onComplete
