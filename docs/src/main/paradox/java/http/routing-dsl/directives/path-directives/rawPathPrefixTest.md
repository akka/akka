# rawPathPrefixTest

@@@ div { .group-scala }

## Signature

@@signature [PathDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #rawPathPrefixTest }

@@@

## Description

Checks whether the unmatched path of the @unidoc[RequestContext] has a prefix matched by the given `PathMatcher`.
Potentially extracts one or more values (depending on the type of the argument) but doesn't consume its match from
the unmatched path.

This directive is very similar to the @ref[pathPrefix](pathPrefix.md) directive with the one difference that the path prefix
it matched (if it matched) is *not* consumed. The unmatched path of the @unidoc[RequestContext] is therefore left as
is even in the case that the directive successfully matched and the request is passed on to its inner route.

For more info on how to create a `PathMatcher` see @ref[The PathMatcher DSL](../../../../../scala/http/routing-dsl/path-matchers.md).

As opposed to its @ref[pathPrefixTest](pathPrefixTest.md) counterpart `rawPathPrefixTest` does *not* automatically add a leading slash
to its `PathMatcher` argument. Rather its `PathMatcher` argument is applied to the unmatched path as is.

Depending on the type of its `PathMatcher` argument the `rawPathPrefixTest` directive extracts zero or more values
from the URI. If the match fails the request is rejected with an @ref[empty rejection set](../../rejections.md#empty-rejections).

## Example

Scala
:  @@snip [PathDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #completeWithUnmatchedPath #rawPathPrefixTest- }

Java
:  @@snip [PathDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #raw-path-prefix-test }
