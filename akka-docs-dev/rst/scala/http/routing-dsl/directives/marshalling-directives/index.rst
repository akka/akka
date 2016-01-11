.. _MarshallingDirectives:

Marshalling Directives
======================

Marshalling directives work in conjunction with ``akka.http.scaladsl.marshalling`` and ``akka.http.scaladsl.unmarshalling`` to convert
a request entity to a specific type or a type to a response.

See :ref:`marshalling <http-marshalling-scala>` and :ref:`unmarshalling <http-unmarshalling-scala>` for specific
serialization (also known as pickling) guidance.

Marshalling directives usually rely on an in-scope implicit marshaller to handle conversion.  

.. toctree::
   :maxdepth: 1

   completeWith
   entity
   handleWith

Understanding Specific Marshalling Directives
---------------------------------------------

======================================= =======================================
directive                               behavior
======================================= =======================================
:ref:`-completeWith-`                   Uses a marshaller for a given type to produce a completion function for an inner route. Used in conjuction with *instanceOf* to format responses.
:ref:`-entity-`                         Unmarshalls the request entity to the given type and passes it to its inner route.  Used in conjection with *as* to convert requests to objects.
:ref:`-handleWith-`                     Completes a request with a given function, using an in-scope unmarshaller for an input and in-scope marshaller for the output.
======================================= =======================================




