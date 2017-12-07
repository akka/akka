# requestEncodedWith

@@@ div { .group-scala }

## Signature

@@signature [CodingDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) { #requestEncodedWith }

@@@

## Description

Passes the request to the inner route if the request is encoded with the argument encoding. Otherwise, rejects the request with an `UnacceptedRequestEncodingRejection(encoding)`.

This directive is the building block for `decodeRequest` to reject unsupported encodings.

## Example

TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: [write example snippets for Akka HTTP Java DSL #218](https://github.com/akka/akka-http/issues/218).