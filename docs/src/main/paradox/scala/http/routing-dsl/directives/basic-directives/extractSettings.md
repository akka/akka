<a id="extractsettings"></a>
# extractSettings

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractSettings }

## Description

Extracts the `RoutingSettings` from the `RequestContext`.

By default the settings of the `Http()` extension running the route will be returned.
It is possible to override the settings for specific sub-routes by using the @ref[withSettings](withSettings.md#withsettings) directive.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractSettings-examples }