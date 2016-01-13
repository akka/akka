.. _-scheme-:

scheme
======

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SchemeDirectives.scala
   :snippet: scheme

Description
-----------
Rejects a request if its Uri scheme does not match a given one.

The ``scheme`` directive can be used to match requests by their Uri scheme, only passing
through requests that match the specified scheme and rejecting all others.

A typical use case for the ``scheme`` directive would be to reject requests coming in over
http instead of https, or to redirect such requests to the matching https URI with a
``MovedPermanently``.

For simply extracting the scheme name, see the :ref:`-extractScheme-` directive.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SchemeDirectivesExamplesSpec.scala
   :snippet: example-2

