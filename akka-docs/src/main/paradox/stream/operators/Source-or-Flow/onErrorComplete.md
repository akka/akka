# onErrorComplete

Allows completing the stream when an upstream error occurs.

@ref[Error handling](../index.md#error-handling)

## Signature

@apidoc[Source.onErrorComplete](Source) { scala="#onErrorComplete(pf%3A%20PartialFunction%5BThrowable%2C%20Boolean%5D)%3AFlowOps.this.Repr%5BT%5D" java="#onErrorComplete(java.util.function.Predicate)" }
@apidoc[Source.onErrorComplete](Source) { scala="#onErrorComplete%5BT%20%3C%3A%20Throwable%5D()(implicit%20tag%3A%20ClassTag%5BT%5D)%3AFlowOps.this.Repr%5BT%5D" java="#onErrorComplete(java.lang.Class)" }
@apidoc[Flow.onErrorComplete](Flow) { scala="#onErrorComplete(pf%3A%20PartialFunction%5BThrowable%2C%20Boolean%5D)%3AFlowOps.this.Repr%5BT%5D" java="#onErrorComplete(java.util.function.Predicate)" }
@apidoc[Flow.onErrorComplete](Flow) { scala="#onErrorComplete%5BT%20%3C%3A%20Throwable%5D()(implicit%20tag%3A%20ClassTag%5BT%5D)%3AFlowOps.this.Repr%5BT%5D" java="#onErrorComplete(java.lang.Class)" }

## Description

Allows to complete the stream when an upstream error occurs.

## Reactive Streams semantics

@@@div { .callout }

**emits** element is available from the upstream

**backpressures** downstream backpressures

**completes** upstream completes or upstream failed with exception this operator can handle

**Cancels when** downstream cancels
@@@

