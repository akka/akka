# Migration Guide to and within Akka HTTP 10.1.x

## General Notes

See the general @ref[compatibility guidelines](../compatibility-guidelines.md).

## Akka HTTP 10.0.11 - > 10.1.0

### Depend on akka-stream explicitly

Starting from Akka Http 10.1.0, an explicit dependency to the `akka-stream` module is necessary. See
@ref[Compatibility with Akka](../compatibility-guidelines.md#compatibility-with-akka) for more information. The minimum
Akka version currently required is Akka 2.5.11.

### Removal of deprecated methods

Methods deprecated during 10.0.x but before 10.0.11 were removed in 10.1.0. When still compiling with 10.0.11 make sure
not to refer to deprecated methods. The deprecation notices usually give hints about alternatives.

### Return type change of ServerBinding.unbind

The return type of `ServerBinding.unbind` has been changed to @scala[`Future[Done]`]@java[`CompletionStage[Done]`] for
consistency. It previously returned an element type of @scala[`Unit`]@java[`BoxedUnit`]. Both the old and new
types can be ignored, so in most cases, no action is necessary. If you typed out the return type somewhere, change the
type to `akka.Done`.