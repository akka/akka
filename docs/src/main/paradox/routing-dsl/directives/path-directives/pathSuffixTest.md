# pathSuffixTest

@@@ div { .group-scala }

## Signature

@@signature [PathDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #pathSuffixTest }

@@@

## Description

Checks whether the unmatched path of the @unidoc[RequestContext] has a suffix matched by the given `PathMatcher`.
Potentially extracts one or more values (depending on the type of the argument) but doesn't consume its match from
the unmatched path.

This directive is very similar to the @ref[pathSuffix](pathSuffix.md) directive with the one difference that the path suffix
it matched (if it matched) is *not* consumed. The unmatched path of the @unidoc[RequestContext] is therefore left as
is even in the case that the directive successfully matched and the request is passed on to its inner route.

As opposed to @ref[pathPrefixTest](pathPrefixTest.md) this directive matches and consumes the unmatched path from the right, i.e. the end.

@@@ warning { title="Caution" }
For efficiency reasons, the given `PathMatcher` must match the desired suffix in reversed-segment
order, i.e. `pathSuffixTest("baz" / "bar")` would match `/foo/bar/baz`! The order within a segment match is
not reversed.
@@@

Depending on the type of its `PathMatcher` argument the `pathSuffixTest` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an @ref[empty rejection set](../../rejections.md#empty-rejections).

## Example

Scala
:  @@snip [PathDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #completeWithUnmatchedPath #pathSuffixTest- }

Java
:  @@snip [PathDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #path-suffix-test }
