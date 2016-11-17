<a id="formfieldmultimap-java"></a>
# formFieldMultiMap

## Description

Extracts all HTTP form fields at once as a multi-map of type `Map<String, <List<String>>` mapping
a form name to a list of all its values. Data posted from [HTML Forms](http://www.w3.org/TR/html401/interact/forms.html#h-17.13.4) is either of type `application/x-www-form-urlencoded` or of type `multipart/form-data`.

This directive can be used if form fields can occur several times.

The order of values is *not* specified.

## Warning

Use of this directive can result in performance degradation or even in `OutOfMemoryError` s.

## Example

@@snip [FormFieldDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/FormFieldDirectivesExamplesTest.java) { #formFieldMultiMap }