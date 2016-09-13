.. _-pathEnd-java-:

pathEnd
=======

Description
-----------
Only passes the request to its inner route if the unmatched path of the ``RequestContext`` is empty, i.e. the request
path has been fully matched by a higher-level :ref:`-path-java-` or :ref:`-pathPrefix-java-` directive.


This directive is a simple alias for ``rawPathPrefix(PathEnd)`` and is mostly used on an
inner-level to discriminate "path already fully matched" from other alternatives (see the example below).


Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java#path-end
