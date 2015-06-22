.. _-respondWithHeaders-:

respondWithHeaders
==================

Adds the given HTTP headers to all responses coming back from its inner route.


Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/RespondWithDirectives.scala
   :snippet: respondWithHeaders


Description
-----------

This directive transforms ``HttpResponse`` and ``ChunkedResponseStart`` messages coming back from its inner route by
adding the given ``HttpHeader`` instances to the headers list.
If you'd like to add just a single header you can use the :ref:`-respondWithHeader-` directive instead.


Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/RespondWithDirectivesExamplesSpec.scala
   :snippet: respondWithHeaders-examples