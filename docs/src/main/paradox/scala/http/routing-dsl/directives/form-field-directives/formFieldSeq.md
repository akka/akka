# formFieldSeq

## Signature

@@signature [FormFieldDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/FormFieldDirectives.scala) { #formFieldSeq }

## Description

Extracts all HTTP form fields at once in the original order as (name, value) tuples of type `(String, String)`.

This directive can be used if the exact order of form fields is important or if parameters can occur several times.

See @ref[formFields](formFields.md) for an in-depth description.

## Warning

The directive reads all incoming HTTP form fields without any configured upper bound.
It means, that requests with form fields holding significant amount of data (ie. during a file upload)
can cause performance issues or even an `OutOfMemoryError` s.

## Example

@@snip [FormFieldDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/FormFieldDirectivesExamplesSpec.scala) { #formFieldSeq }