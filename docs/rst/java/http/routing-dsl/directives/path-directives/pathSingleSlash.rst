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
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java#path-single-slash
