.. _-authenticateOrRejectWithChallenge-:

authenticateOrRejectWithChallenge
=================================

Signature
---------

.. includecode:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala#authentication-result

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala
   :snippet: authenticateOrRejectWithChallenge

Description
-----------
Lifts an authenticator function into a directive.

This directive allows implementing the low level challange-response type of authentication that some services may require.

More details about challenge-response authentication are available in the `RFC 2617`_, `RFC 7616`_ and `RFC 7617`_.

.. _RFC 2617: http://tools.ietf.org/html/rfc2617
.. _RFC 7616: http://tools.ietf.org/html/rfc7616
.. _RFC 7617: http://tools.ietf.org/html/rfc7617

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala
   :snippet: authenticateOrRejectWithChallenge-0
