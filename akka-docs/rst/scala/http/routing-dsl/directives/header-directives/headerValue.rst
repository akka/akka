.. _-headerValue-:

headerValue
===========

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: headerValue

Description
-----------
Traverses the list of request headers with the specified function and extracts the first value the function returns as
``Some(value)``.

The :ref:`-headerValue-` directive is a mixture of ``map`` and ``find`` on the list of request headers. The specified function
is called once for each header until the function returns ``Some(value)``. This value is extracted and presented to the
inner route. If the function throws an exception the request is rejected with a ``MalformedHeaderRejection``. If the
function returns ``None`` for every header the request is rejected as "NotFound".

This directive is the basis for building other request header related directives.

See also :ref:`-headerValuePF-` for a nicer syntactic alternative.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: headerValue-0
