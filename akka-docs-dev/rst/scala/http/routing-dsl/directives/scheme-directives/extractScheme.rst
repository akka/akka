.. _-extractScheme-:

extractScheme
=============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SchemeDirectives.scala
   :snippet: extractScheme

Description
-----------
Extracts the Uri scheme (i.e. "``http``", "``https``", etc.) for an incoming request.

For rejecting a request if it doesn't match a specified scheme name, see the :ref:`-scheme-` directive.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SchemeDirectivesExamplesSpec.scala
   :snippet: example-1
