.. _-responseEncodingAccepted-java-:

responseEncodingAccepted
========================

Description
-----------

Passes the request to the inner route if the request accepts the argument encoding. Otherwise, rejects the request with an ``UnacceptedResponseEncodingRejection(encoding)``.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java#responseEncodingAccepted
