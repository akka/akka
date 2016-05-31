.. _-onCompleteWithBreaker-java-:

onCompleteWithBreaker
=====================

Description
-----------
Evaluates its parameter of type ``CompletionStage<T>`` protecting it with the specified ``CircuitBreaker``.
Refer to :ref:`Akka Circuit Breaker<circuit-breaker>` for a detailed description of this pattern.

If the ``CircuitBreaker`` is open, the request is rejected with a ``CircuitBreakerOpenRejection``.
Note that in this case the request's entity databytes stream is cancelled, and the connection is closed
as a consequence.

Otherwise, the same behaviour provided by :ref:`-onComplete-java-` is to be expected.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
