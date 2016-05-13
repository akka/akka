.. _-pathEndOrSingleSlash-java-:

pathEndOrSingleSlash
====================

Description
-----------
Only passes the request to its inner route if the unmatched path of the ``RequestContext`` is either empty
or contains only one single slash.

This directive is a simple alias for ``rawPathPrefix(Slash.? ~ PathEnd)`` and is mostly used on an inner-level to
discriminate "path already fully matched" from other alternatives (see the example below).

It is equivalent to ``pathEnd | pathSingleSlash`` but slightly more efficient.


Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
