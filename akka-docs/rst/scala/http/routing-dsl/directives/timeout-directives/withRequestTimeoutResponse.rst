.. _-withRequestTimeoutResponse-:

withRequestTimeoutResponse
==========================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/TimeoutDirectives.scala
   :snippet: withRequestTimeoutResponse

Description
-----------

Allows customising the ``HttpResponse`` that will be sent to clients in case of a :ref:`request-timeout-scala`.

See also :ref:`-withRequestTimeout-` or :ref:`-withoutRequestTimeout-` if interested in dynamically changing the timeout
for a given route instead.

.. warning::
  Please note that setting handler is inherently racy as the timeout is measured from starting to handle the request
  to its deadline, thus if the timeout triggers before the ``withRequestTimeoutResponse`` executed it would have emitted
  the default timeout HttpResponse.

  In practice this can only be a problem with very tight timeouts, so with default settings
  of request timeouts being measured in seconds it shouldn't be a problem in reality (though certainly a possibility still).

To learn more about various timeouts in Akka HTTP and how to configure them see :ref:`http-timeouts-scala`.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/TimeoutDirectivesExamplesSpec.scala
   :snippet: withRequestTimeoutResponse
