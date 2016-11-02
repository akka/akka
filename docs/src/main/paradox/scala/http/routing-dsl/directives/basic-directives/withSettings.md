<a id="withsettings"></a>
# withSettings

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #withSettings }

## Description

Allows running an inner route using an alternative `RoutingSettings` in place of the default one.

The execution context can be extracted in an inner route using @ref[extractSettings](extractSettings.md#extractsettings) directly,
or used by directives which internally extract the materializer without surfacing this fact in the API.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #withSettings-0 }