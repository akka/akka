.. _-path-java-:

path
====

Description
-----------
Matches the complete unmatched path of the ``RequestContext`` against the given ``PathMatcher``, potentially extracts
one or more values (depending on the type of the argument).

This directive filters incoming requests based on the part of their URI that hasn't been matched yet by other
potentially existing :ref:`-pathPrefix-java-` directives on higher levels of the routing structure.
Its one parameter is usually an expression evaluating to a ``PathMatcher`` instance (see also: :ref:`pathmatcher-dsl`).

As opposed to the :ref:`-rawPathPrefix-java-` or :ref:`-rawPathPrefixTest-java-` directives ``path`` automatically adds a leading
slash to its ``PathMatcher`` argument, you therefore don't have to start your matching expression with an explicit slash.

The ``path`` directive attempts to match the **complete** remaining path, not just a prefix. If you only want to match
a path prefix and then delegate further filtering to a lower level in your routing structure use the :ref:`-pathPrefix-java-`
directive instead. As a consequence it doesn't make sense to nest a ``path`` or :ref:`-pathPrefix-java-` directive
underneath another ``path`` directive, as there is no way that they will ever match (since the unmatched path underneath
a ``path`` directive will always be empty).

Depending on the type of its ``PathMatcher`` argument the ``path`` directive extracts zero or more values from the URI.
If the match fails the request is rejected with an :ref:`empty rejection set <empty rejections>`.


Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
