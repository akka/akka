<a id="formfieldmap-java"></a>
# formFieldMap

## Description

Extracts all HTTP form fields at once as a `Map<String, String>` mapping form field names to form field values. Data posted from [HTML Forms](http://www.w3.org/TR/html401/interact/forms.html#h-17.13.4) is either of type `application/x-www-form-urlencoded` or of type `multipart/form-data`.

If form data contain a field value several times, the map will contain the last one.

## Warning

Use of this directive can result in performance degradation or even in `OutOfMemoryError` s.
See @ref[formFieldList](formFieldList.md#formfieldlist-java) for details.

## Example

@@snip [FormFieldDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/FormFieldDirectivesExamplesTest.java) { #formFieldMap }