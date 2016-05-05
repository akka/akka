.. _-extractRequest-java-:

extractRequest
==============

Description
-----------
Extracts the complete ``HttpRequest`` instance.

Use ``extractRequest`` to extract just the complete URI of the request. Usually there's little use of
extracting the complete request because extracting of most of the aspects of HttpRequests is handled by specialized
directives. See :ref:`Request Directives-java`.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractRequest-example
