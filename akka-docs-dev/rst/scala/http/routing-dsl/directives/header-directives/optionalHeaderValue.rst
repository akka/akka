.. _-optionalHeaderValue-:

optionalHeaderValue
===================

Traverses the list of request headers with the specified function and extracts the first value the function returns as
``Some(value)``.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: optionalHeaderValue

Description
-----------

The ``optionalHeaderValue`` directive is similar to the ``headerValue`` directive but always extracts an ``Option``
value instead of rejecting the request if no matching header could be found.
