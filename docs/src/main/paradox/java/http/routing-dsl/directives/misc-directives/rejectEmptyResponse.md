# rejectEmptyResponse

@@@ div { .group-scala }

## Signature

@@signature [MiscDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala) { #rejectEmptyResponse }

@@@

## Description

Replaces a response with no content with an empty rejection.

The `rejectEmptyResponse` directive is mostly used with marshalling `Optional<T>` instances. The value `None` is
usually marshalled to an empty but successful result. In many cases `None` should instead be handled as
`404 Not Found` which is the effect of using `rejectEmptyResponse`.

## Example

TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: [write example snippets for Akka HTTP Java DSL #218](https://github.com/akka/akka-http/issues/218).