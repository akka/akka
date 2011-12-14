Typed Actors (Scala)
====================

.. sidebar:: Contents

   .. contents:: :local:

Akka Typed Actors is an implementation of the `Active Objects <http://en.wikipedia.org/wiki/Active_object>`_ pattern.
Essentially turning method invocations into asynchronous dispatch instead of synchronous that has been the default way since Smalltalk came out.

Typed Actors consist of 2 "parts", a public interface and an implementation, and if you've done any work in "enterprise" Java, this will be very familiar to you. As with normal Actors you have an external API (the public interface instance) that will delegate methodcalls asynchronously to
a private instance of the implementation.

The advantage of Typed Actors vs. Actors is that with TypedActors you have a static contract, and don't need to define your own messages, the downside is that it places some limitations on what you can do and what you can't, i.e. you can't use become/unbecome.

Typed Actors are implemented using `JDK Proxies <http://docs.oracle.com/javase/6/docs/api/java/lang/reflect/Proxy.html>`_ which provide a pretty easy-worked API to intercept method calls.

Typed Actor is an Akka Extension and could as such be implemented as a User Level API, but is provided in the core Akka package.

Method dispatch semantics
-------------------------

Methods returning:

  * ``Unit`` will be dispatched with ``fire-and-forget`` semantics, exactly like :meth:``Actor.tell``
  * ``akka.dispatch.Future[_]`` will use ``send-request-reply`` semantics, exactly like :meth:``Actor.ask``
  * ``scala.Option[_]`` or akka.japi.Option[_] will use ``send-request-reply`` semantics, but _will_ block to wait for an answer,
    and return None if no answer was produced within the timout, or scala.Some/akka.japi.Some containing the result otherwise.
    Any exception that was thrown during this call will be rethrown.
  * Any other type of value will use ``send-request-reply`` semantics, but _will_ block to wait for an answer,
    throwing ``java.util.concurrent.TimeoutException`` if there was a timeout or rethrow any exception that was thrown during this call.

Creating Typed Actors
---------------------

To create a Typed Actor you need to have one or more interfaces, and one implementation:

.. includecode:: code/TypedActorDocSpec.scala
   :include: imports,typed-actor-create

If you need to call a specific constructor you do it like this:

INSERT EXAMPLE HERE

Since you supply a Props, you can specify which dispatcher to use etc.

Sending messages
----------------

One-way message send
^^^^^^^^^^^^^^^^^^^^

Request-reply message send
^^^^^^^^^^^^^^^^^^^^^^^^^^

Request-reply-with-future message send
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Stopping Typed Actors
---------------------

Lifecycle callbacks
-------------------

Messages and immutability
-------------------------