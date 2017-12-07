# withoutSizeLimit

@@@ div { .group-scala }

## Signature

@@signature [MiscDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala) { #withoutSizeLimit }

@@@

## Description

Skips request entity size verification.

The whole mechanism of entity size checking is intended to prevent certain Denial-of-Service attacks.
So suggested setup is to have `akka.http.parsing.max-content-length` relatively low and use `withoutSizeLimit`
directive just for endpoints for which size verification should not be performed.

@@@ warning { title="Caution" }
Usage of `withoutSizeLimit` is not recommended as it turns off the too large payload protection. Therefore, we highly 
encourage using `withSizeLimit` instead, providing it with a value high enough to successfully handle the 
route in need of big entities.
@@@

See also @ref[withSizeLimit](withSizeLimit.md) for setting request entity size limit.

## Example

Scala
:  @@snip [MiscDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #withoutSizeLimit-example }

Java
:  @@snip [MiscDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #withSizeLimitExample }
