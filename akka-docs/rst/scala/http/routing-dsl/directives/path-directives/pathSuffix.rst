.. _-pathSuffix-:

pathSuffix
==========

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala
   :snippet: pathSuffix


Description
-----------
Matches and consumes a suffix of the unmatched path of the ``RequestContext`` against the given ``PathMatcher``,
potentially extracts one or more values (depending on the type of the argument).

This directive filters incoming requests based on the part of their URI that hasn't been matched yet by other
potentially existing path matching directives on higher levels of the routing structure.
Its one parameter is usually an expression evaluating to a ``PathMatcher`` instance (see also: :ref:`pathmatcher-dsl`).

As opposed to :ref:`-pathPrefix-` this directive matches and consumes the unmatched path from the right, i.e. the end.

.. caution:: For efficiency reasons, the given ``PathMatcher`` must match the desired suffix in reversed-segment
   order, i.e. ``pathSuffix("baz" / "bar")`` would match ``/foo/bar/baz``! The order within a segment match is
   not reversed.

Depending on the type of its ``PathMatcher`` argument the ``pathPrefix`` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an :ref:`empty rejection set <empty rejections>`.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala
   :snippet: pathSuffix-