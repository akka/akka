<a id="recoverrejectionswith"></a>
# recoverRejectionsWith

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #recoverRejectionsWith }

## Description

**Low level directive** – unless you're sure you need to be working on this low-level you might instead
want to try the @ref[handleRejections](../execution-directives/handleRejections.md#handlerejections) directive which provides a nicer DSL for building rejection handlers.

Transforms rejections from the inner route with an `immutable.Seq[Rejection] ⇒ Future[RouteResult]` function.

Asynchronous version of @ref[recoverRejections](recoverRejections.md#recoverrejections).

See @ref[recoverRejections](recoverRejections.md#recoverrejections) (the synchronous equivalent of this directive) for a detailed description.

@@@ note
To learn more about how and why rejections work read the @ref[Rejections](../../rejections.md#rejections-scala) section of the documentation.
@@@

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #recoverRejectionsWith }
