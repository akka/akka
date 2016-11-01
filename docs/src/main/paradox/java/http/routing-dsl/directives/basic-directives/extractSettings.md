<a id="extractsettings-java"></a>
# extractSettings

## Description

Extracts the `RoutingSettings` from the `RequestContext`.

By default the settings of the `Http()` extension running the route will be returned.
It is possible to override the settings for specific sub-routes by using the @ref[withSettings](withSettings.md#withsettings-java) directive.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractRequestContext }