.. _-extractRequest-:

extractRequest
==============

Extracts the complete ``HttpRequest`` instance.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractRequest

Description
-----------

Use ``extractRequest`` to extract just the complete URI of the request. Usually there's little use of
extracting the complete request because extracting of most of the aspects of HttpRequests is handled by specialized
directives. See :ref:`Request Directives`.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractRequest-example
