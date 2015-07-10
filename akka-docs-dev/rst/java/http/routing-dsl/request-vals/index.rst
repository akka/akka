.. _request-vals-java:

Request values
==============

A request value of type ``RequestVal<T>`` is a typed structure that represents some aspect of the request
that can be interpreted as a value of type ``T``. A ``RequestVal`` instance abstracts the knowledge about how
to extract a certain value from the request and interpret it as a ``T``. It is used in combination with
:ref:`handlers-java`.

The advantage of representing a request detail as a ``RequestVal`` instead of performing ad-hoc analysis of
a request are:

 * you can define an "inventory" of HTTP primitives for your application that you can reuse in many places of your
   application
 * automatic handling of errors when an expected value was not found in a request or if it could not be interpreted
   as the expected Java type

Note, that the Scala version of the routing DSL has no direct correspondent to RequestVals. Instead,
a Scala-side ``Directive`` can have "extractions" that are reflected in the type of the ``Directive``.

Predefined Request values
-------------------------

akka-http provides a set of predefined request values for request data commonly accessed in a web
service.

These request values are defined:

 * in ``RequestVals``: request values for basic data like URI components, request method, peer address, or the entity data
 * in ``Cookies``: request values representing cookies
 * in ``FormFields``: request values to access form fields unmarshalled to various primitive Java types
 * in ``Headers``:: request values to access request headers or header values
 * ``HttpBasicAuthenticator``: an abstract class to implement to create a request value representing a HTTP basic authenticated principal
 * in ``Parameters``: request values to access URI paramaters unmarshalled to various primitive Java types
 * in ``PathMatchers``: request values to match and access URI path segments
 * ``CustomRequestVal``: an abstract class to implement arbitrary custom request values
