.. _-withRequestTimeoutResponse-java-:

withRequestTimeoutResponse
==========================

Description
-----------

Allows customising the ``HttpResponse`` that will be sent to clients in case of a :ref:`request-timeout-java`.

See also :ref:`-withRequestTimeout-java-` or :ref:`-withoutRequestTimeout-java-` if interested in dynamically changing the timeout
for a given route instead.

.. warning::
  Please note that setting handler is inherently racy as the timeout is measured from starting to handle the request
  to its deadline, thus if the timeout triggers before the ``withRequestTimeoutResponse`` executed it would have emitted
  the default timeout HttpResponse.

  In practice this can only be a problem with very tight timeouts, so with default settings
  of request timeouts being measured in seconds it shouldn't be a problem in reality (though certainly a possibility still).

To learn more about various timeouts in Akka HTTP and how to configure them see :ref:`http-timeouts-java`.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
