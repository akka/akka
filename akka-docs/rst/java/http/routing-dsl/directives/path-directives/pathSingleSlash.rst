.. _-pathSingleSlash-java-:

pathSingleSlash
===============

Description
-----------
Only passes the request to its inner route if the unmatched path of the ``RequestContext``
contains exactly one single slash.

This directive is a simple alias for ``pathPrefix(PathEnd)`` and is mostly used for matching requests to the root URI
(``/``) on an inner-level to discriminate "all path segments matched" from other alternatives (see the example below).


Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
