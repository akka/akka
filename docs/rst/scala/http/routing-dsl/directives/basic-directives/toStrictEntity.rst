.. _-toStrictEntity-:

toStrictEntity
==============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: toStrictEntity

Description
-----------

Transforms the request entity to strict entity before it is handled by the inner route.

A timeout parameter is given and if the stream isn't completed after the timeout, the directive will be failed.

.. warning::

  The directive will read the request entity into memory within the size limit(8M by default) and effectively disable streaming.
  The size limit can be configured globally with ``akka.http.parsing.max-content-length`` or
  overridden by wrapping with :ref:`-withSizeLimit-` or :ref:`-withoutSizeLimit-` directive.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: toStrictEntity-example
