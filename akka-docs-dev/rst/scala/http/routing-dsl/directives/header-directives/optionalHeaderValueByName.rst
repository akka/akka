.. _-optionalHeaderValueByName-:

optionalHeaderValueByName
=========================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: optionalHeaderValueByName

Description
-----------
Optionally extracts the value of the HTTP request header with the given name.

The ``optionalHeaderValueByName`` directive is similar to the :ref:`-headerValueByName-` directive but always extracts
an ``Option`` value instead of rejecting the request if no matching header could be found.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: optionalHeaderValueByName-0