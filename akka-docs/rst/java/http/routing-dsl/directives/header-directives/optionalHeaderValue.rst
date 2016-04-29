.. _-optionalHeaderValue-java-:

optionalHeaderValue
===================

Description
-----------
Traverses the list of request headers with the specified function and extracts the first value the function returns as
``Some(value)``.

The ``optionalHeaderValue`` directive is similar to the :ref:`-headerValue-java-` directive but always extracts an ``Option``
value instead of rejecting the request if no matching header could be found.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: optionalHeaderValue-0
