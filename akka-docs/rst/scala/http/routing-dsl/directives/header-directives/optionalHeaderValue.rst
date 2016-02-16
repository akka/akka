.. _-optionalHeaderValue-:

optionalHeaderValue
===================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: optionalHeaderValue

Description
-----------
Traverses the list of request headers with the specified function and extracts the first value the function returns as
``Some(value)``.

The ``optionalHeaderValue`` directive is similar to the :ref:`-headerValue-` directive but always extracts an ``Option``
value instead of rejecting the request if no matching header could be found.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: optionalHeaderValue-0
