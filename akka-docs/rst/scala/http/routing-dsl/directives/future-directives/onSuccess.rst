.. _-onSuccess-:

onSuccess
=========

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FutureDirectives.scala
   :snippet: onSuccess

Description
-----------
Evaluates its parameter of type ``Future[T]``, and once the ``Future`` has been completed successfully,
extracts its result as a value of type ``T`` and passes it to the inner route.

If the future fails its failure throwable is bubbled up to the nearest ``ExceptionHandler``.

To handle the ``Failure`` case manually as well, use :ref:`-onComplete-`, instead.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala
   :snippet: onSuccess
