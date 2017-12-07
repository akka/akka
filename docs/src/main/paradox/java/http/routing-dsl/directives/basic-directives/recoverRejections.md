# recoverRejections

## Description

**Low level directive** – unless you're sure you need to be working on this low-level you might instead
want to try the @ref[handleRejections](../execution-directives/handleRejections.md) directive which provides a nicer DSL for building rejection handlers.

Transforms rejections from the inner route with a `Function<Iterable<Rejection>, RouteResult>`.
A @unidoc[RouteResult] is either a `Complete` containing the @unidoc[HttpResponse] or a `Rejected` containing the
rejections.

@@@ note
To learn more about how and why rejections work read the @ref[Rejections](../../rejections.md) section of the documentation.
@@@

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #recoverRejections }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #recoverRejections }
