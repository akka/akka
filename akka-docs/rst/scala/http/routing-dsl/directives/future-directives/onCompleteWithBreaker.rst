.. _-onCompleteWithBreaker-:

onCompleteWithBreaker
=====================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FutureDirectives.scala
   :snippet: onCompleteWithBreaker

Description
-----------
Evaluates its parameter of type ``Future[T]`` protecting it with the specified ``CircuitBreaker``.
Refer to :ref:`Akka Circuit Breaker<circuit-breaker>` for a detailed description of this pattern.

If the ``CircuitBreaker`` is open, the request is rejected with a ``CircuitBreakerOpenRejection``.
Note that in this case the request's entity databytes stream is cancelled, and the connection is closed
as a consequence.

Otherwise, the same behaviour provided by :ref:`-onComplete-` is to be expected.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala
   :snippet: onCompleteWithBreaker
