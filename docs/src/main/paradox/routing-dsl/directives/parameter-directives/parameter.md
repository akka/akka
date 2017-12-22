# parameter

@@@ div { .group-scala }

## Signature

@@signature [ParameterDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/ParameterDirectives.scala) { #parameter }

@@@

## Description

Extracts a *query* parameter value from the @scala[request]@java[request and provides it to the inner route as a `String`].

@scala[In the Scala API, `parameter` is an alias for `parameters` and you can use both directives to extract any number of parameter values.]
For a detailed description about how to extract one or more parameters see @ref[parameters](parameters.md).

See @ref[When to use which parameter directive?](index.md#which-parameter-directive) to understand when to use which directive.

## Example

Scala
:  @@snip [ParameterDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala) { #example-1 }

Java
:  @@snip [ParameterDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/ParameterDirectivesExamplesTest.java) { #parameter }
