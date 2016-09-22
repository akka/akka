<a id="parametermap-java"></a>
# parameterMap

Extracts all parameters at once as a `Map<String, String>` mapping parameter names to parameter values.

## Description

If a query contains a parameter value several times, the map will contain the last one.

See also @ref[When to use which parameter directive?](index.md#which-parameter-directive-java) to understand when to use which directive.

## Example

@@snip [ParameterDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #parameterMap }