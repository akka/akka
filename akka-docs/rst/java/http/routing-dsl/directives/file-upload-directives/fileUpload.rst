.. _-fileUpload-java-:

fileUpload
==========

Description
-----------
Simple access to the stream of bytes for a file uploaded as a multipart form together with metadata
about the upload as extracted value.

If there is no field with the given name the request will be rejected, if there are multiple file parts
with the same name, the first one will be used and the subsequent ones ignored.


Example
-------
.. includecode2:: ../../../../code/docs/http/javadsl/server/directives/FileUploadDirectivesExamplesTest.java
   :snippet: fileUpload

::

   curl --form "csv=@uploadFile.txt" http://<host>:<port>
