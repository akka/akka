.. _-withLog-java-:

withLog
=======

Description
-----------

Allows running an inner route using an alternative :class:`LoggingAdapter` in place of the default one.

The logging adapter can be extracted in an inner route using :ref:`-extractLog-java-` directly,
or used by directives which internally extract the materializer without surfacing this fact in the API.


Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#withLog
