<a id="storeuploadedfiles-java"></a>
# storeUploadedFiles

## Description

Streams the contents of all files uploaded in a multipart form into files on disk and provides a list of each
file and metadata about the upload.

If there is an error writing to disk the request will be failed with the thrown exception. If there is no field
with the given name the request will be rejected.

## Example

@@snip [FileUploadDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FileUploadDirectivesExamplesTest.java) { #storeUploadedFiles }
