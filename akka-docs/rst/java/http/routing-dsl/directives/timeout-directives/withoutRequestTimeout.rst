.. _-withoutRequestTimeout-java-:

withoutRequestTimeout
=====================

Description
-----------

This directive enables "late" (during request processing) control over the :ref:`request-timeout-java` feature in Akka HTTP.

It is not recommended to turn off request timeouts using this method as it is inherently racy and disabling request timeouts
basically turns off the safety net against programming mistakes that it provides.

.. warning::
  Please note that setting the timeout from within a directive is inherently racy (as the "point in time from which
  we're measuring the timeout" is already in the past (the moment we started handling the request), so if the existing
  timeout already was triggered before your directive had the chance to change it, an timeout may still be logged.

For more information about various timeouts in Akka HTTP see :ref:`http-timeouts-java`.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
