.. _-optionalHeaderValuePF-:

optionalHeaderValuePF
=====================

Calls the specified partial function with the first request header the function is ``isDefinedAt`` and extracts the
result of calling the function.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: optionalHeaderValuePF

Description
-----------

The ``optionalHeaderValuePF`` directive is similar to the ``headerValuePF`` directive but always extracts an ``Option``
value instead of rejecting the request if no matching header could be found.
