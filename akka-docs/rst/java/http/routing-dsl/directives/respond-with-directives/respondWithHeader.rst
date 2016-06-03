.. _-respondWithHeader-java-:

respondWithHeader
=================

Description
-----------
Adds a given HTTP header to all responses coming back from its inner route.

This directive transforms ``HttpResponse`` and ``ChunkedResponseStart`` messages coming back from its inner route by
adding the given ``HttpHeader`` instance to the headers list.

See also :ref:`-respondWithHeaders-java-` if you'd like to add more than one header.

Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/RespondWithDirectivesExamplesTest.java#respondWithHeader
