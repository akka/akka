.. _-extractDataBytes-:

extractDataBytes
================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractDataBytes

Description
-----------

Extracts the entities data bytes as ``Source[ByteString, Any]`` from the :class:`RequestContext`.

The directive returns a stream containing the request data bytes.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractDataBytes-example
