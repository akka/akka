.. _form-field-request-vals-java:

Request Values: FormFields
==========================

A collection of pre-defined :ref:`request-vals-java` that can be used to extract header values from incoming requests.

Description
-----------
``FormField`` request values allow extracting fields submitted as ``application/x-www-form-urlencoded`` values or concrete instances from HTTP requests.

The ``RequestVal`` builder is made up of 2 steps, initially you need to pick which Header to extract (``byName`` or
``byClass``) and then you need to pick if the header is optionally available or required (i.e. the route should not
match if the header is not present in the request). This is done using one of the below depicted methods::

Examples
--------

Extracting form fields of a certain primitive type from a request:

.. includecode:: ../../../code/docs/http/javadsl/server/FormFieldRequestValsExampleTest.java#simple

Extracting values of custom type from a request by providing a conversion function:

.. includecode:: ../../../code/docs/http/javadsl/server/FormFieldRequestValsExampleTest.java#custom-unmarshal
