.. _-headerValueByName-java-:

headerValueByName
=================

Description
-----------
Extracts the value of the HTTP request header with the given name.

If no header with a matching name is found the request is rejected with a ``MissingHeaderRejection``.

If the header is expected to be missing in some cases or to customize
handling when the header is missing use the :ref:`-optionalHeaderValueByName-java-` directive instead.

Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java#headerValueByName