# rawPathPrefix

@@@ div { .group-scala }

## Signature

@@signature [PathDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #rawPathPrefix }

@@@

## Description

Matches and consumes a prefix of the unmatched path of the @unidoc[RequestContext] against the given `PathMatcher`,
potentially extracts one or more values (depending on the type of the argument).

This directive filters incoming requests based on the part of their URI that hasn't been matched yet by other
potentially existing `rawPathPrefix` or @ref[pathPrefix](pathPrefix.md) directives on higher levels of the routing structure.
Its one parameter is usually an expression evaluating to a `PathMatcher` instance (see also: @ref[The PathMatcher DSL](../../path-matchers.md)).

As opposed to its @ref[pathPrefix](pathPrefix.md) counterpart `rawPathPrefix` does *not* automatically add a leading slash to its
`PathMatcher` argument. Rather its `PathMatcher` argument is applied to the unmatched path as is. For a comparison between path directives check @ref[Overview of path directives](index.md#overview-path-scala).

Depending on the type of its `PathMatcher` argument the `rawPathPrefix` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an @ref[empty rejection set](../../rejections.md#empty-rejections).

## Example

Scala
:  @@snip [PathDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #completeWithUnmatchedPath #rawPathPrefix- }

Java
:  @@snip [PathDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #raw-path-prefix-test }
