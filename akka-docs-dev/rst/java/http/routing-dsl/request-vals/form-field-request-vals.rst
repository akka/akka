.. _form-field-request-vals-java:

Request Values: FormFields
==========================

A collection of pre-defined :ref:`request-vals-java` that can be used to extract header values from incoming requests.

Description
-----------
``FormField`` request values allow extracting fields submitted as ``application/x-www-form-urlencoded`` values or concrete instances from HTTP requests.

The ``FormField`` request value builder is made up of 2 steps, initially you need to pick which what type of value you
want to extract from the field (for example ``intValue``, which would reject the route if the value is not an ``int``),
and then **optionally** you may specify if the value is optional (by calling ``optional()`` on the ``RequestVal``)
or has a default value (by calling ``withDefault()`` on the ``RequestVal``).

Examples
--------

Extracting form fields of a certain primitive type from a request:

.. includecode:: ../../../code/docs/http/javadsl/server/FormFieldRequestValsExampleTest.java#simple

Extracting values of custom type from a request by providing a conversion function:

.. includecode:: ../../../code/docs/http/javadsl/server/FormFieldRequestValsExampleTest.java#custom-unmarshal
