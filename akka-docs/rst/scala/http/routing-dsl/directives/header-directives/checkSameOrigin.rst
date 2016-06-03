.. _-checkSameOrigin-:

checkSameOrigin
===============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: checkSameOrigin

Description
-----------
Checks that request comes from the same origin. Extracts the ``Origin`` header value and verifies that allowed range
contains the obtained value. In the case of absent of the ``Origin`` header rejects with a ``MissingHeaderRejection``.
If the origin value is not in the allowed range rejects with an ``InvalidOriginHeaderRejection``
and ``StatusCodes.Forbidden`` status.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: checkSameOrigin-0
