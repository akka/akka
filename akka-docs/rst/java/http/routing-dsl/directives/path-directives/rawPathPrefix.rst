.. _-rawPathPrefix-java-:

rawPathPrefix
=============

Description
-----------
Matches and consumes a prefix of the unmatched path of the ``RequestContext`` against the given ``PathMatcher``,
potentially extracts one or more values (depending on the type of the argument).

This directive filters incoming requests based on the part of their URI that hasn't been matched yet by other
potentially existing ``rawPathPrefix`` or :ref:`-pathPrefix-java-` directives on higher levels of the routing structure.
Its one parameter is usually an expression evaluating to a ``PathMatcher`` instance (see also: :ref:`pathmatcher-dsl`).

As opposed to its :ref:`-pathPrefix-java-` counterpart ``rawPathPrefix`` does *not* automatically add a leading slash to its
``PathMatcher`` argument. Rather its ``PathMatcher`` argument is applied to the unmatched path as is.

Depending on the type of its ``PathMatcher`` argument the ``rawPathPrefix`` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an :ref:`empty rejection set <empty rejections>`.


Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
