<a id="mapsettings"></a>
# mapSettings

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapSettings }

## Description

Transforms the `RoutingSettings` with a `RoutingSettings ⇒ RoutingSettings` function.

See also @ref[withSettings](withSettings.md#withsettings) or @ref[extractSettings](extractSettings.md#extractsettings).

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #withSettings-0 }