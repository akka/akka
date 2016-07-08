.. _-path-:

path
====

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala
   :snippet: path


Description
-----------
Matches the complete unmatched path of the ``RequestContext`` against the given ``PathMatcher``, potentially extracts
one or more values (depending on the type of the argument).

This directive filters incoming requests based on the part of their URI that hasn't been matched yet by other
potentially existing :ref:`-pathPrefix-` directives on higher levels of the routing structure.
Its one parameter is usually an expression evaluating to a ``PathMatcher`` instance (see also: :ref:`pathmatcher-dsl`).

As opposed to the :ref:`-rawPathPrefix-` or :ref:`-rawPathPrefixTest-` directives ``path`` automatically adds a leading
slash to its ``PathMatcher`` argument, you therefore don't have to start your matching expression with an explicit slash.

The ``path`` directive attempts to match the **complete** remaining path, not just a prefix. If you only want to match
a path prefix and then delegate further filtering to a lower level in your routing structure use the :ref:`-pathPrefix-`
directive instead. As a consequence it doesn't make sense to nest a ``path`` or :ref:`-pathPrefix-` directive
underneath another ``path`` directive, as there is no way that they will ever match (since the unmatched path underneath
a ``path`` directive will always be empty).

Depending on the type of its ``PathMatcher`` argument the ``path`` directive extracts zero or more values from the URI.
If the match fails the request is rejected with an :ref:`empty rejection set <empty rejections>`.

.. note:: The empty string (also called empty word or identity) is a **neutral element** of string concatenation operation,
 so it will match everything, but remember that ``path`` requires whole remaining path being matched, so (``/``) will succeed
 and (``/whatever``) will fail. The :ref:`-pathPrefix-` provides more liberal behaviour.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala
  :snippet: path-example
