.. _-headerValueByName-:

headerValueByName
=================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: headerValueByName

Description
-----------
Extracts the value of the HTTP request header with the given name.

The name can be given as a ``String`` or as a ``Symbol``. If no header with a matching name is found the request
is rejected with a ``MissingHeaderRejection``.

If the header is expected to be missing in some cases or to customize
handling when the header is missing use the :ref:`-optionalHeaderValueByName-` directive instead.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: headerValueByName-0
