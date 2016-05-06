.. _-scheme-java-:

scheme
======

Description
-----------
Rejects a request if its Uri scheme does not match a given one.

The ``scheme`` directive can be used to match requests by their Uri scheme, only passing
through requests that match the specified scheme and rejecting all others.

A typical use case for the ``scheme`` directive would be to reject requests coming in over
http instead of https, or to redirect such requests to the matching https URI with a
``MovedPermanently``.

For simply extracting the scheme name, see the :ref:`-extractScheme-java-` directive.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
