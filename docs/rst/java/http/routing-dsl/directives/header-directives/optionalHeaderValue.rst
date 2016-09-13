.. _-optionalHeaderValue-java-:

optionalHeaderValue
===================

Description
-----------
Traverses the list of request headers with the specified function and extracts the first value the function returns as
``Optional[value]``.

The ``optionalHeaderValue`` directive is similar to the :ref:`-headerValue-java-` directive but always extracts an ``Option``
value instead of rejecting the request if no matching header could be found.

Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java#optionalHeaderValue