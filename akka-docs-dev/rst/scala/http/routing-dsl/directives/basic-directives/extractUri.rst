.. _-extractUri-:

extractUri
==========

Access the full URI of the request.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractUri

Description
-----------

Use :ref:`SchemeDirectives`, :ref:`HostDirectives`, :ref:`PathDirectives`,  and :ref:`ParameterDirectives` for more
targeted access to parts of the URI.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractUri-example
