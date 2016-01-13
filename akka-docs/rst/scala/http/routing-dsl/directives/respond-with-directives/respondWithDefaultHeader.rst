.. _-respondWithDefaultHeader-:

respondWithDefaultHeader
========================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/RespondWithDirectives.scala
   :snippet: respondWithDefaultHeader


Description
-----------
Adds a given HTTP header to all responses coming back from its inner route only if a header with the same name doesn't
exist yet in the response.


This directive transforms ``HttpResponse`` and ``ChunkedResponseStart`` messages coming back from its inner route by
potentially adding the given ``HttpHeader`` instance to the headers list.
The header is only added if there is no header instance with the same name (case insensitively) already present in the
response.

See also :ref:`-respondWithDefaultHeaders-`  if you'd like to add more than one header.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/RespondWithDirectivesExamplesSpec.scala
   :snippet: respondWithDefaultHeader-0
