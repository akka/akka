.. _-onSuccess-java-:

onSuccess
=========

Description
-----------
Evaluates its parameter of type ``CompletionStage<T>``, and once it has been completed successfully,
extracts its result as a value of type ``T`` and passes it to the inner route.

If the future fails its failure throwable is bubbled up to the nearest ``ExceptionHandler``.

To handle the ``Failure`` case manually as well, use :ref:`-onComplete-java-`, instead.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
