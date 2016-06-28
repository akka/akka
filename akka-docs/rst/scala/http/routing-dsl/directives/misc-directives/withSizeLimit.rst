.. _-withSizeLimit-:

withSizeLimit
===============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala
   :snippet: withSizeLimit

Description
-----------
Fails the stream with ``EntityStreamSizeException`` if its request entity size exceeds given limit. Limit given
as parameter overrides limit configured with ``akka.http.parsing.max-content-length``.

The whole mechanism of entity size checking is intended to prevent certain Denial-of-Service attacks.
So suggested setup is to have ``akka.http.parsing.max-content-length`` relatively low and use ``withSizeLimit``
directive for endpoints which expects bigger entities.

See also :ref:`-withoutSizeLimit-` for skipping request entity size check.

Examples
--------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: withSizeLimit-example

Beware that request entity size check is executed when entity is consumed. Therefore in the following example
even request with entity greater than argument to ``withSizeLimit`` will succeed (because this route
does not consume entity):

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: withSizeLimit-execution-moment-example

Directive ``withSizeLimit`` is implemented in terms of ``HttpEntity.withSizeLimit`` which means that in case of
nested ``withSizeLimit`` directives the innermost is applied:

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: withSizeLimit-nested-example
