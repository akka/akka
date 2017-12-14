# pathSuffix

@@@ div { .group-scala }

## Signature

@@signature [PathDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #pathSuffix }

@@@

## Description

Matches and consumes a suffix of the unmatched path of the @unidoc[RequestContext] against the given `PathMatcher`,
potentially extracts one or more values (depending on the type of the argument).

This directive filters incoming requests based on the part of their URI that hasn't been matched yet by other
potentially existing path matching directives on higher levels of the routing structure.
Its one parameter is usually an expression evaluating to a `PathMatcher` instance (see also: @ref[The PathMatcher DSL](../../../../../scala/http/routing-dsl/path-matchers.md)).

As opposed to @ref[pathPrefix](pathPrefix.md) this directive matches and consumes the unmatched path from the right, i.e. the end.

@@@ warning { title="Caution" }
For efficiency reasons, the given `PathMatcher` must match the desired suffix in reversed-segment
order, i.e. `pathSuffix("baz" / "bar")` would match `/foo/bar/baz`! The order within a segment match is
not reversed.
@@@

Depending on the type of its `PathMatcher` argument the `pathPrefix` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an @ref[empty rejection set](../../rejections.md#empty-rejections).

## Example

Scala
:  @@snip [PathDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #completeWithUnmatchedPath #pathSuffix- }

Java
:  @@snip [PathDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #path-suffix }
