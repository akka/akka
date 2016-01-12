.. _-rawPathPrefixTest-:

rawPathPrefixTest
=================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala
   :snippet: rawPathPrefixTest


Description
-----------
Checks whether the unmatched path of the ``RequestContext`` has a prefix matched by the given ``PathMatcher``.
Potentially extracts one or more values (depending on the type of the argument) but doesn't consume its match from
the unmatched path.

This directive is very similar to the :ref:`-pathPrefix-` directive with the one difference that the path prefix
it matched (if it matched) is *not* consumed. The unmatched path of the ``RequestContext`` is therefore left as
is even in the case that the directive successfully matched and the request is passed on to its inner route.

For more info on how to create a ``PathMatcher`` see :ref:`pathmatcher-dsl`.

As opposed to its :ref:`-pathPrefixTest-` counterpart ``rawPathPrefixTest`` does *not* automatically add a leading slash
to its ``PathMatcher`` argument. Rather its ``PathMatcher`` argument is applied to the unmatched path as is.

Depending on the type of its ``PathMatcher`` argument the ``rawPathPrefixTest`` directive extracts zero or more values
from the URI. If the match fails the request is rejected with an :ref:`empty rejection set <empty rejections>`.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala
   :snippet: rawPathPrefixTest-