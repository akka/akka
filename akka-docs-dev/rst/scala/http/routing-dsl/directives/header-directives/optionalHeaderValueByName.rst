.. _-optionalHeaderValueByName-:

optionalHeaderValueByName
=========================

Optionally extracts the value of the HTTP request header with the given name.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: optionalHeaderValueByName

Description
-----------

The ``optionalHeaderValueByName`` directive is similar to the ``headerValueByName`` directive but always extracts
an ``Option`` value instead of rejecting the request if no matching header could be found.
