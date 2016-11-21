<a id="extractclientip-java"></a>
# extractClientIP

## Description

Provides the value of `X-Forwarded-For`, `Remote-Address`, or `X-Real-IP` headers as an instance of `HttpIp`.

The akka-http server engine adds the `Remote-Address` header to every request automatically if the respective
setting `akka.http.server.remote-address-header` is set to `on`. Per default it is set to `off`.

## Example

@@snip [MiscDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #extractClientIPExample }