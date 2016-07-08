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
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java#path-end-or-single-slash
