<a id="pathprefixtest"></a>
# pathPrefixTest

## Signature

@@signature [PathDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #pathPrefixTest }

## Description

Checks whether the unmatched path of the `RequestContext` has a prefix matched by the given `PathMatcher`.
Potentially extracts one or more values (depending on the type of the argument) but doesn't consume its match from
the unmatched path.

This directive is very similar to the @ref[pathPrefix](pathPrefix.md#pathprefix) directive with the one difference that the path prefix
it matched (if it matched) is *not* consumed. The unmatched path of the `RequestContext` is therefore left as
is even in the case that the directive successfully matched and the request is passed on to its inner route.

For more info on how to create a `PathMatcher` see @ref[The PathMatcher DSL](../../path-matchers.md#pathmatcher-dsl).

As opposed to its @ref[rawPathPrefixTest](rawPathPrefixTest.md#rawpathprefixtest) counterpart `pathPrefixTest` automatically adds a leading slash to its
`PathMatcher` argument, you therefore don't have to start your matching expression with an explicit slash.

Depending on the type of its `PathMatcher` argument the `pathPrefixTest` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an @ref[empty rejection set](../../rejections.md#empty-rejections).

## Example

@@snip [PathDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #completeWithUnmatchedPath #pathPrefixTest- }