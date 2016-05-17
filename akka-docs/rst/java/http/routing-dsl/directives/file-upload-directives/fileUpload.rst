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
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.

::

   curl --form "csv=@uploadFile.txt" http://<host>:<port>
