.. _-responseEncodingAccepted-:

responseEncodingAccepted
========================

Signature
---------
TODO: Add example snippet.
.. 
.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala
   :snippet: responseEncodingAccepted

Description
-----------

Passes the request to the inner route if the request accepts the argument encoding. Otherwise, rejects the request with an ``UnacceptedResponseEncodingRejection(encoding)``.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala
  :snippet: responseEncodingAccepted
