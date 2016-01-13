.. _-rawPathPrefix-:

rawPathPrefix
=============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala
   :snippet: rawPathPrefix


Description
-----------
Matches and consumes a prefix of the unmatched path of the ``RequestContext`` against the given ``PathMatcher``,
potentially extracts one or more values (depending on the type of the argument).

This directive filters incoming requests based on the part of their URI that hasn't been matched yet by other
potentially existing ``rawPathPrefix`` or :ref:`-pathPrefix-` directives on higher levels of the routing structure.
Its one parameter is usually an expression evaluating to a ``PathMatcher`` instance (see also: :ref:`pathmatcher-dsl`).

As opposed to its :ref:`-pathPrefix-` counterpart ``rawPathPrefix`` does *not* automatically add a leading slash to its
``PathMatcher`` argument. Rather its ``PathMatcher`` argument is applied to the unmatched path as is.

Depending on the type of its ``PathMatcher`` argument the ``rawPathPrefix`` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an :ref:`empty rejection set <empty rejections>`.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala
   :snippet: rawPathPrefix-