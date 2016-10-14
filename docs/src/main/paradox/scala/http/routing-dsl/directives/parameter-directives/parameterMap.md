<a id="parametermap"></a>
# parameterMap

## Signature

@@signature [ParameterDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ParameterDirectives.scala) { #parameterMap }

## Description

Extracts all parameters at once as a `Map[String, String]` mapping parameter names to parameter values.

If a query contains a parameter value several times, the map will contain the last one.

See also @ref[When to use which parameter directive?](index.md#which-parameter-directive) to understand when to use which directive.

## Example

@@snip [ParameterDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #parameterMap }