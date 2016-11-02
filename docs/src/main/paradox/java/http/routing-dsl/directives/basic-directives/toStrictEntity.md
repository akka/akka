<a id="tostrictentity-java"></a>
# toStrictEntity

## Description

Transforms the request entity to strict entity before it is handled by the inner route.

A timeout parameter is given and if the stream isn't completed after the timeout, the directive will be failed.

@@@ warning

The directive will read the request entity into memory within the size limit(8M by default) and effectively disable streaming.
The size limit can be configured globally with `akka.http.parsing.max-content-length` or
overridden by wrapping with @ref[withSizeLimit](../misc-directives/withSizeLimit.md#withsizelimit-java) or @ref[withoutSizeLimit-java](../misc-directives/withoutSizeLimit.md#withoutsizelimit-java) directive.

@@@

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #toStrictEntity }
