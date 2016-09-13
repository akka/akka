.. _-uploadedFile-java-:

uploadedFile
============

Description
-----------
Streams the contents of a file uploaded as a multipart form into a temporary file on disk and provides the file and
metadata about the upload as extracted value.

If there is an error writing to disk the request will be failed with the thrown exception, if there is no field
with the given name the request will be rejected, if there are multiple file parts with the same name, the first
one will be used and the subsequent ones ignored.

.. note::
   This directive will stream contents of the request into a file, however one can not start processing these
   until the file has been written completely. For streaming APIs it is preferred to use the :ref:`-fileUpload-java-`
   directive, as it allows for streaming handling of the incoming data bytes.


Example
-------
.. includecode2:: ../../../../code/docs/http/javadsl/server/directives/FileUploadDirectivesExamplesTest.java
   :snippet: uploadedFile