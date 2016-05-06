.. _-respondWithHeader-java-:

respondWithHeader
=================

Description
-----------
Adds a given HTTP header to all responses coming back from its inner route.

This directive transforms ``HttpResponse`` and ``ChunkedResponseStart`` messages coming back from its inner route by
adding the given ``HttpHeader`` instance to the headers list.

See also :ref:`-respondWithHeaders-java-` if you'd like to add more than one header.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
