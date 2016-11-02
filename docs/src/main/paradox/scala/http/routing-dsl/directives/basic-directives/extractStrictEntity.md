<a id="extractstrictentity"></a>
# extractStrictEntity

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractStrictEntity }

## Description

Extracts the strict http entity as `HttpEntity.Strict` from the `RequestContext`.

A timeout parameter is given and if the stream isn't completed after the timeout, the directive will be failed.

@@@ warning

The directive will read the request entity into memory within the size limit(8M by default) and effectively disable streaming.
The size limit can be configured globally with `akka.http.parsing.max-content-length` or
overridden by wrapping with @ref[withSizeLimit](../misc-directives/withSizeLimit.md#withsizelimit) or @ref[withoutSizeLimit](../misc-directives/withoutSizeLimit.md#withoutsizelimit) directive.

@@@

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractStrictEntity-example }
