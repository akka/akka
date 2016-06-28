.. _-withSizeLimit-java-:

withSizeLimit
===============

Description
-----------
Fails the stream with ``EntityStreamSizeException`` if its request entity size exceeds given limit. Limit given
as parameter overrides limit configured with ``akka.http.parsing.max-content-length``.

The whole mechanism of entity size checking is intended to prevent certain Denial-of-Service attacks.
So suggested setup is to have ``akka.http.parsing.max-content-length`` relatively low and use ``withSizeLimit``
directive for endpoints which expects bigger entities.

See also :ref:`-withoutSizeLimit-java-` for skipping request entity size check.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java#withSizeLimitExample
