# fileUpload

@@@ div { .group-scala }

## Signature

@@signature [FileUploadDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/FileUploadDirectives.scala) { #fileUpload }

@@@

## Description

Simple access to the stream of bytes for a file uploaded as a multipart form together with metadata
about the upload.

If there is no field with the given name the request will be rejected. If there are multiple file parts
with the same name, the first one will be used and the subsequent ones ignored.

@@@ note
This directive will only upload one file with a given name. To upload multiple files with the same name
you should use the @ref[fileUploadAll](fileUploadAll.md#fileuploadall) directive, though all files will
be buffered to disk, even if there is only one.
@@@

## Example

Scala
:  @@snip [FileUploadDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/FileUploadDirectivesExamplesSpec.scala) { #fileUpload }

Java
:  @@snip [FileUploadDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FileUploadDirectivesExamplesTest.java) { #fileUpload }
