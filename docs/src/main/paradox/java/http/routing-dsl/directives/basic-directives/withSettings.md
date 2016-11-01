<a id="withsettings-java"></a>
# withSettings

## Description

Allows running an inner route using an alternative `RoutingSettings` in place of the default one.

The execution context can be extracted in an inner route using @ref[extractSettings](extractSettings.md#extractsettings-java) directly,
or used by directives which internally extract the materializer without surfacing this fact in the API.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #withSettings }