.. _-respondWithHeaders-java-:

respondWithHeaders
==================

Description
-----------
Adds the given HTTP headers to all responses coming back from its inner route.

This directive transforms ``HttpResponse`` and ``ChunkedResponseStart`` messages coming back from its inner route by
adding the given ``HttpHeader`` instances to the headers list.

See also :ref:`-respondWithHeader-java-` if you'd like to add just a single header.


Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/RespondWithDirectivesExamplesTest.java#respondWithHeaders
