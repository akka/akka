.. _-optionalHeaderValuePF-java-:

optionalHeaderValuePF
=====================

Description
-----------
Calls the specified partial function with the first request header the function is ``isDefinedAt`` and extracts the
result of calling the function.

The ``optionalHeaderValuePF`` directive is similar to the :ref:`-headerValuePF-java-` directive but always extracts an ``Optional``
value instead of rejecting the request if no matching header could be found.

Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java#optionalHeaderValuePF