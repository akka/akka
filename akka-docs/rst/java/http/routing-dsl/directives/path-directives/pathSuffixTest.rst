.. _-pathSuffixTest-java-:

pathSuffixTest
==============

Description
-----------
Checks whether the unmatched path of the ``RequestContext`` has a suffix matched by the given ``PathMatcher``.
Potentially extracts one or more values (depending on the type of the argument) but doesn't consume its match from
the unmatched path.

This directive is very similar to the :ref:`-pathSuffix-java-` directive with the one difference that the path suffix
it matched (if it matched) is *not* consumed. The unmatched path of the ``RequestContext`` is therefore left as
is even in the case that the directive successfully matched and the request is passed on to its inner route.

As opposed to :ref:`-pathPrefixTest-java-` this directive matches and consumes the unmatched path from the right, i.e. the end.

.. caution:: For efficiency reasons, the given ``PathMatcher`` must match the desired suffix in reversed-segment
   order, i.e. ``pathSuffixTest("baz" / "bar")`` would match ``/foo/bar/baz``! The order within a segment match is
   not reversed.

Depending on the type of its ``PathMatcher`` argument the ``pathSuffixTest`` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an :ref:`empty rejection set <empty rejections>`.


Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
