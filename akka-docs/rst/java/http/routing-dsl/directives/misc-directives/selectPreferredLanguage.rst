.. _-selectPreferredLanguage-java-:

selectPreferredLanguage
=======================

Description
-----------
Inspects the request's ``Accept-Language`` header and determines,
which of a given set of language alternatives is preferred by the client according to content negotiation rules
defined by http://tools.ietf.org/html/rfc7231#section-5.3.5.

If there are several best language alternatives that the client has equal preference for
(even if this preference is zero!) the order of the arguments is used as a tie breaker (first one wins).

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
