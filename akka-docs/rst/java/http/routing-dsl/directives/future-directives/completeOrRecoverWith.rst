.. _-completeOrRecoverWith-java-:

completeOrRecoverWith
=====================

Description
-----------
"Unwraps" a ``CompletionStage<T>`` and runs the inner route when the stage has failed
with the stage's failure exception as an extraction of type ``Throwable``.
If the completion stage succeeds the request is completed using the values marshaller
(This directive therefore requires a marshaller for the completion stage value type to be
provided.)

To handle the successful case manually as well, use the :ref:`-onComplete-java-` directive, instead.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
