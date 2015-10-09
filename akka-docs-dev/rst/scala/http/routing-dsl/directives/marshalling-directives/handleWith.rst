.. _-handleWith-:

handleWith
==========

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MarshallingDirectives.scala
   :snippet: handleWith

Description
-----------
Completes the request using the given function. The input to the function is produced with
the in-scope entity unmarshaller and the result value of the function is marshalled with
the in-scope marshaller.  ``handleWith`` can be a convenient method combining ``entity`` with
``complete``.

The ``handleWith`` directive is used when you want to handle a route with a given function of
type A ⇒ B.  ``handleWith`` will use both an in-scope unmarshaller to convert a request into 
type A and an in-scope marshaller to convert type B into a response. This is helpful when your 
core business logic resides in some other class or you want your business logic to be independent
of the REST interface written with akka-http. You can use ``handleWith`` to "hand off" processing
to a given function without requiring any akka-http-specific functionality.

``handleWith`` is similar to ``produce``.  The main difference is ``handleWith`` automatically
calls ``complete`` when the function passed to ``handleWith`` returns. Using ``produce`` you
must explicity call the completion function passed from the ``produce`` function.

See :ref:`marshalling <http-marshalling-scala>` and :ref:`unmarshalling <http-unmarshalling-scala>` for guidance
on marshalling entities with akka-http.

Examples
--------

The following example uses an ``updatePerson`` function with a ``Person`` case class as an input and output.  We plug this function into our route using ``handleWith``.

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MarshallingDirectivesExamplesSpec.scala
   :snippet: person-case-class

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MarshallingDirectivesExamplesSpec.scala
   :snippet: example-handleWith-with-json

The PersonJsonSupport object handles both marshalling and unmarshalling of the Person case class.

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MarshallingDirectivesExamplesSpec.scala
   :snippet: person-json-support
