# parameter

Extracts a *query* parameter value from the request.

@@@ div { .group-scala }

## Signature

@@signature [ParameterDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/ParameterDirectives.scala) { #parameter }

@@@

## Description

See @ref[parameters](parameters.md) for a detailed description of this directive.

See @ref[When to use which parameter directive?](index.md#which-parameter-directive) to understand when to use which directive.

## Example

Scala
:  @@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #example-1 }

Java
:  @@snip [ParameterDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #parameter }
