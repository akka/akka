.. _-extractScheme-:

extractScheme
=============

Extracts the value of the request Uri scheme.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/SchemeDirectives.scala
   :snippet: extractScheme

Description
-----------

The ``extractScheme`` directive can be used to determine the Uri scheme (i.e. "http", "https", etc.)
for an incoming request.

For rejecting a request if it doesn't match a specified scheme name, see the :ref:`-scheme-` directive.

Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SchemeDirectivesExamplesSpec.scala
   :snippet: example-1
