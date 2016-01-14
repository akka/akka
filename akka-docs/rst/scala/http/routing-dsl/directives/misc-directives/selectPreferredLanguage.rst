.. _-selectPreferredLanguage-:

selectPreferredLanguage
=======================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala
   :snippet: selectPreferredLanguage

Description
-----------
Inspects the request's ``Accept-Language`` header and determines,
which of a given set of language alternatives is preferred by the client according to content negotiation rules
defined by http://tools.ietf.org/html/rfc7231#section-5.3.5.

If there are several best language alternatives that the client has equal preference for
(even if this preference is zero!) the order of the arguments is used as a tie breaker (first one wins).

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: selectPreferredLanguage-example
