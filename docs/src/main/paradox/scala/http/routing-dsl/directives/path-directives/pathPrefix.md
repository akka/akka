# pathPrefix

@@@ div { .group-scala }

## Signature

@@signature [PathDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #pathPrefix }

@@@

## Description

Matches and consumes a prefix of the unmatched path of the @unidoc[RequestContext] against the given `PathMatcher`,
potentially extracts one or more values (depending on the type of the argument).

This directive filters incoming requests based on the part of their URI that hasn't been matched yet by other
potentially existing `pathPrefix` or @ref[rawPathPrefix](rawPathPrefix.md) directives on higher levels of the routing structure.
Its one parameter is usually an expression evaluating to a `PathMatcher` instance (see also: @ref[The PathMatcher DSL](../../path-matchers.md)).

As opposed to its @ref[rawPathPrefix](rawPathPrefix.md) counterpart `pathPrefix` automatically adds a leading slash to its
`PathMatcher` argument, you therefore don't have to start your matching expression with an explicit slash. For a comparison between path directives check @ref[Overview of path directives](index.md#overview-path-scala).

Depending on the type of its `PathMatcher` argument the `pathPrefix` directive extracts zero or more values from
the URI. If the match fails the request is rejected with an @ref[empty rejection set](../../rejections.md#empty-rejections).

@@@ note
The empty string (also called empty word or identity) is a **neutral element** of string concatenation operation,
so it will match everything and consume nothing. The @ref[path](path.md) provides more strict behaviour.
@@@

## Example

Scala
:  @@snip [PathDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #pathPrefix- }

Java
:  @@snip [PathDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #path-prefix }
