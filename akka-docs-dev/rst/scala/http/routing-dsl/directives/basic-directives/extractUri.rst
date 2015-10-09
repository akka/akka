.. _-extractUri-:

extractUri
==========

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractUri

Description
-----------
Access the full URI of the request.

Use :ref:`SchemeDirectives`, :ref:`HostDirectives`, :ref:`PathDirectives`,  and :ref:`ParameterDirectives` for more
targeted access to parts of the URI.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractUri-example
