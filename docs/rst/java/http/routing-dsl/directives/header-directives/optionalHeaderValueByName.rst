.. _-optionalHeaderValueByName-java-:

optionalHeaderValueByName
=========================

Description
-----------
Optionally extracts the value of the HTTP request header with the given name.

The ``optionalHeaderValueByName`` directive is similar to the :ref:`-headerValueByName-java-` directive but always extracts
an ``Optional`` value instead of rejecting the request if no matching header could be found.

Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java#optionalHeaderValueByName