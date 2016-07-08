.. _-authenticateOrRejectWithChallenge-java-:

authenticateOrRejectWithChallenge
=================================
Lifts an authenticator function into a directive.

Description
-----------
This directive allows implementing the low level challange-response type of authentication that some services may require.

More details about challenge-response authentication are available in the `RFC 2617`_, `RFC 7616`_ and `RFC 7617`_.

.. _RFC 2617: http://tools.ietf.org/html/rfc2617
.. _RFC 7616: http://tools.ietf.org/html/rfc7616
.. _RFC 7617: http://tools.ietf.org/html/rfc7617

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/SecurityDirectivesExamplesTest.java#authenticateOrRejectWithChallenge
