.. _-onComplete-java-:

onComplete
==========

Description
-----------
Evaluates its parameter of type ``CompletionStage<T>``, and once it has been completed, extracts its
result as a value of type ``Try<T>`` and passes it to the inner route. A ``Try<T>`` can either be a ``Success`` containing
the ``T`` value or a ``Failure`` containing the ``Throwable``.

To handle the ``Failure`` case automatically and only work with the result value, use :ref:`-onSuccess-java-`.

To complete with a successful result automatically and just handle the failure result, use :ref:`-completeOrRecoverWith-java-`, instead.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
