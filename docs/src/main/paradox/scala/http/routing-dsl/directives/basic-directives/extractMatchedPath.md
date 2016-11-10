<a id="extractmatchedpathtext"></a>
# extractMatchedPath

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractMatchedPath }

## Description

Extracts the matched path from the request context.

The `extractMatchedPath` directive extracts the path that was already matched by any of the @ref[PathDirectives](../path-directives/index.md#pathdirectives)
(or any custom ones that change the unmatched path field of the request context). You can use it for building directives
that use already matched part in their logic.

See also @ref[extractUnmatchedPath](extractUnmatchedPath.md#extractunmatchedpath) to see similar directive for unmatched path.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractMatchedPath-example }