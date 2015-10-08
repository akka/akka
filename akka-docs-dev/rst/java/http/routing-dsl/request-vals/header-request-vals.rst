.. _header-request-vals-java:

Request Values: Headers
=======================

A collection of pre-defined :ref:`request-vals-java` that can be used to extract header values from incoming requests.

Description
-----------
Header request values allow extracting ``HttpHeader`` values or concrete instances from HTTP requests.

The ``RequestVal`` builder is made up of 2 steps, initially you need to pick which Header to extract (``byName`` or
``byClass``) and then you need to pick if the header is optionally available or required (i.e. the route should not
match if the header is not present in the request). This is done using one of the below depicted methods::

  RequestVal<T> instance()
  RequestVal<<Option<T>> optionalInstance()

  RequestVal<String> value()
  RequestVal<Option<String>> optionalValue()

Examples
--------

Extracting a header by using a specific ``Header`` class (which are pre-defined in ``akka.http.javadsl.model.headers.*``):

.. includecode:: ../../../code/docs/http/javadsl/server/HeaderRequestValsExampleTest.java
   :include: by-class

Extracting arbitrary headers by their name, for example custom headers (usually starting with ``X-...``):

.. includecode:: ../../../code/docs/http/javadsl/server/HeaderRequestValsExampleTest.java
   :include: by-name
