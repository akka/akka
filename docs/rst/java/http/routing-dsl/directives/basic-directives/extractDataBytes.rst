.. _-extractDataBytes-java-:

extractDataBytes
================

Description
-----------

Extracts the entities data bytes as ``Source[ByteString, Any]`` from the :class:`RequestContext`.

The directive returns a stream containing the request data bytes.

Example
-------

.. includecode:: ../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#extractDataBytes
