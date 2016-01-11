.. _-pass-:

pass
====

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: pass

Description
-----------
A directive that passes the request unchanged to its inner route.

It is usually used as a "neutral element" when combining directives generically.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: pass
