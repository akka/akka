<a id="extractunmatchedpath"></a>
# extractUnmatchedPath

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractUnmatchedPath }

## Description

Extracts the unmatched path from the request context.

The `extractUnmatchedPath` directive extracts the remaining path that was not yet matched by any of the @ref[PathDirectives](../path-directives/index.md#pathdirectives)
(or any custom ones that change the unmatched path field of the request context). You can use it for building directives
that handle complete suffixes of paths (like the `getFromDirectory` directives and similar ones).

Use `mapUnmatchedPath` to change the value of the unmatched path.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractUnmatchedPath-example }