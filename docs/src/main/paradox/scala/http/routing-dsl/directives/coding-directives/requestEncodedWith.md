<a id="requestencodedwith"></a>
# requestEncodedWith

## Signature

@@signature [CodingDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) { #requestEncodedWith }

## Description

Passes the request to the inner route if the request is encoded with the argument encoding. Otherwise, rejects the request with an `UnacceptedRequestEncodingRejection(encoding)`.

This directive is the @github[building block](/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) for `decodeRequest` to reject unsupported encodings.