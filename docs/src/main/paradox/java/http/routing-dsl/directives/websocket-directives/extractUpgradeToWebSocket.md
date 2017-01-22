# extractUpgradeToWebSocket

## Description

Extracts the @javadoc:[UpgradeToWebSocket](akka.http.javadsl.model.ws.UpgradeToWebSocket) header if existent. Rejects with an @javadoc:[ExpectedWebSocketRequestRejection](akka.http.javadsl.server.ExpectedWebSocketRequestRejection), otherwise.

The `extractUpgradeToWebSocket` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to provide the extracted header to the inner route.

## Example

@@snip [WebSocketDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/WebSocketDirectivesExamplesTest.java) { #extractUpgradeToWebSocket }
