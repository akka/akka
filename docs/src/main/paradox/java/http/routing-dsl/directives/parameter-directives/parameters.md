# parameters

Extracts multiple *query* parameter values from the request.

If an unmarshaller throws an exception while extracting the value of a parameter, the request will be rejected with a `MissingQueryParameterRejection`
if the unmarshaller threw an `Unmarshaller.NoContentException` or a @unidoc[MalformedQueryParamRejection] in all other cases.
(see also @ref[Rejections](../../../routing-dsl/rejections.md))

## Description

See @ref[When to use which parameter directive?](index.md#which-parameter-directive) to understand when to use which directive.

## Example

Scala
:  @@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #mapped-repeated }

Java
:  @@snip [ParameterDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #parameters }
