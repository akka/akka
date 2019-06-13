# Modules marked "May Change"

To be able to introduce new modules and APIs without freezing them the moment they
are released because of our @ref:[binary compatibility guarantees](binary-compatibility-rules.md) we have introduced
the term **may change**.

Concretely **may change** means that an API or module is in early access mode and that it:

 * is not covered by Lightbend's commercial support (unless specifically stated otherwise)
 * is not guaranteed to be binary compatible in minor releases
 * may have its API change in breaking ways in minor releases
 * may be entirely dropped from Akka in a minor release

Complete modules can be marked as **may change**, this will can be found in their module description and in the docs.

Individual public APIs can be annotated with `akka.api.annotation.ApiMayChange` to signal that it has less
guarantees than the rest of the module it lives in. For example, when while introducing "new" Java 8 APIs into
existing stable modules, these APIs may be marked with this annotation to signal that they are not frozen yet.
Please use such methods and classes with care, however if you see such APIs that is the best point in time to try them
out and provide feedback (e.g. using the akka-user mailing list, GitHub issues or Gitter) before they are frozen as
fully stable API.

Best effort migration guides may be provided, but this is decided on a case-by-case basis for **may change** modules.

The purpose of this is to be able to release features early and
make them easily available and improve based on feedback, or even discover
that the module or API wasn't useful.

These are the current complete modules marked as **may change**:

* @ref:[Multi Node Testing](../multi-node-testing.md)


