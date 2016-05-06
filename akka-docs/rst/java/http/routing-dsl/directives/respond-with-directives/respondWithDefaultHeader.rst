.. _-respondWithDefaultHeader-java-:

respondWithDefaultHeader
========================

Description
-----------
Adds a given HTTP header to all responses coming back from its inner route only if a header with the same name doesn't
exist yet in the response.


This directive transforms ``HttpResponse`` and ``ChunkedResponseStart`` messages coming back from its inner route by
potentially adding the given ``HttpHeader`` instance to the headers list.
The header is only added if there is no header instance with the same name (case insensitively) already present in the
response.

See also :ref:`-respondWithDefaultHeaders-java-`  if you'd like to add more than one header.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
