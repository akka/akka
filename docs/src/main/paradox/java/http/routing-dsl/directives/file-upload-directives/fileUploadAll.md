<a id="fileuploadall"></a>
# fileUploadAll

## Description

Simple access to streams of bytes for all files uploaded in a multipart form together with metadata
about each upload.

If there is no field with the given name the request will be rejected.

@@@ note
This directive buffers all files to temporary files on disk in files prefixed `akka-http-upload`. This is
to work around limitations of the HTTP multipart format. To upload only one file it may be preferred to
use the @ref[fileUpload](fileUpload.md#fileupload) directive, as it streams the file directly without
buffering.
@@@

## Example

Scala
:  @@snip [FileUploadDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/FileUploadDirectivesExamplesSpec.scala) { #fileUploadAll }

Java
:  @@snip [FileUploadDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FileUploadDirectivesExamplesTest.java) { #fileUploadAll }
