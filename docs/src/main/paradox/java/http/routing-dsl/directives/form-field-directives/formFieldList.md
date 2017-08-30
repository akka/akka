# formFieldList

## Description

Extracts all HTTP form fields at once in the original order as (name, value) tuples of type `Map.Entry<String, String>`. Data posted from [HTML Forms](http://www.w3.org/TR/html401/interact/forms.html#h-17.13.4) is either of type `application/x-www-form-urlencoded` or of type `multipart/form-data`.

This directive can be used if the exact order of form fields is important or if parameters can occur several times. 

## Warning

The directive reads all incoming HTTP form fields without any configured upper bound.
It means, that requests with form fields holding significant amount of data (ie. during a file upload)
can cause performance issues or even an `OutOfMemoryError` s.

## Example

@@snip [FormFieldDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FormFieldDirectivesExamplesTest.java) { #formFieldList }