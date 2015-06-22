.. _-onSuccess-:

onSuccess
=========

Evaluates its parameter of type ``Future[T]``, and once the ``Future`` has been completed successfully,
extracts its result as a value of type ``T`` and passes it to the inner route.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FutureDirectives.scala
   :snippet: onSuccess

Description
-----------

The execution of the inner route passed to a onSuccess directive is deferred until the given future
has completed successfully, exposing the future's value as a extraction of type ``T``. If the future
fails its failure throwable is bubbled up to the nearest ``ExceptionHandler``.

To handle the ``Failure`` case manually as well, use :ref:`-onComplete-`, instead.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala
   :snippet: onSuccess
