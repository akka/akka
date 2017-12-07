# formField

@@@ div { .group-scala }

## Signature

@@signature [FormFieldDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/FormFieldDirectives.scala) { #formField }

@@@

## Description

Allows extracting a single Form field sent in the request.

See @ref[formFields](formFields.md) for an in-depth description.

## Example

Scala
:  @@snip [FormFieldDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/FormFieldDirectivesExamplesSpec.scala) { #formField }

Java
:  @@snip [FormFieldDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FormFieldDirectivesExamplesTest.java) { #formField }
