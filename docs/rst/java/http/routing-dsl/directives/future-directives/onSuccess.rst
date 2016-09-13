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
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/FutureDirectivesExamplesTest.java#onSuccess
