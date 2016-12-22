# extractParserSettings

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractParserSettings }

## Description

Extracts the request's underlying @scaladoc:[ParserSettings](akka.http.scaladsl.settings.ParserSettings), which can be useful when you want to access custom status codes and media types.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractParserSettings-example }
