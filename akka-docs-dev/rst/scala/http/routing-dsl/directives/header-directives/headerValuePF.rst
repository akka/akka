.. _-headerValuePF-:

headerValuePF
=============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: headerValuePF

Description
-----------
Calls the specified partial function with the first request header the function is ``isDefinedAt`` and extracts the
result of calling the function.

The ``headerValuePF`` directive is an alternative syntax version of :ref:`-headerValue-`.

If the function throws an exception the request is rejected with a ``MalformedHeaderRejection``.

If the function is not defined for any header the request is rejected as "NotFound".

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: headerValuePF-0
