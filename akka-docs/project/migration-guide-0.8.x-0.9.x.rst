Migration Guide 0.8.x to 0.9.x
==============================

**This document describes between the 0.8.x and the 0.9 release.**

Background for the new ActorRef
-------------------------------

In the work towards 0.9 release we have now done a major change to how Actors are created. In short we have separated identity and value, created an 'ActorRef' that holds the actual Actor instance. This allows us to do many great things such as for example:

* Create serializable, immutable, network-aware Actor references that can be freely shared across the network. They "remember" their origin and will always work as expected.
* Not only kill and restart the same supervised Actor instance when it has crashed (as we do now), but dereference it, throw it away and make it eligible for garbage collection.
* etc. much more

These work very much like the 'PID' (process id) in Erlang.

These changes means that there is no difference in defining Actors. You still use the old Actor trait, all methods are there etc. But you can't just new this Actor up and send messages to it since all its public API methods are gone. They now reside in a new class; 'ActorRef' and use need to use instances of this class to interact with the Actor (sending messages etc.).

Here is a short migration guide with the things that you have to change. It is a big conceptual change but in practice you don't have to change much.



Creating Actors with default constructor
----------------------------------------

From:

.. code-block:: scala

  val a = new MyActor
  a ! msg

To:

.. code-block:: scala

  import Actor._
  val a = actorOf(Props[MyActor]
  a ! msg

You can also start it in the same statement:

.. code-block:: scala

  val a = actorOf(Props[MyActor]

Creating Actors with non-default constructor
--------------------------------------------

From:

.. code-block:: scala

  val a = new MyActor(..)
  a ! msg

To:

.. code-block:: scala

  import Actor._
  val a = actorOf(Props(new MyActor(..))
  a ! msg

Use of 'self' ActorRef API
--------------------------

Where you have used 'this' to refer to the Actor from within itself now use 'self':

.. code-block:: scala

  self ! MessageToMe

Now the Actor trait only has the callbacks you can implement:
* receive
* postRestart/preRestart
* init/shutdown

It has no state at all.

All API has been moved to ActorRef. The Actor is given its ActorRef through the 'self' member variable.
Here you find functions like:
* !, !!, !!! and forward
* link, unlink, startLink, spawnLink etc
* makeTransactional, makeRemote etc.
* start, stop
* etc.

Here you also find fields like
* dispatcher = ...
* id = ...
* lifeCycle = ...
* faultHandler = ...
* trapExit = ...
* etc.

This means that to use them you have to prefix them with 'self', like this:

.. code-block:: scala

  self ! Message

However, for convenience you can import these functions and fields like below, which will allow you do drop the 'self' prefix:

.. code-block:: scala

  class MyActor extends Actor {
    import self._
    id = ...
    dispatcher = ...
    spawnLink[OtherActor]
    ...
  }

Serialization
-------------

If you want to serialize it yourself, here is how to do it:

.. code-block:: scala

  val actorRef1 = actorOf(Props[MyActor]

  val bytes = actorRef1.toBinary

  val actorRef2 = ActorRef.fromBinary(bytes)

If you are also using Protobuf then you can use the methods that work with Protobuf's Messages directly.

.. code-block:: scala

  val actorRef1 = actorOf(Props[MyActor]

  val protobufMessage = actorRef1.toProtocol

  val actorRef2 = ActorRef.fromProtocol(protobufMessage)

Camel
-----

Some methods of the se.scalablesolutions.akka.camel.Message class have been deprecated in 0.9. These are

.. code-block:: scala

  package se.scalablesolutions.akka.camel

  case class Message(...) {
    // ...
    @deprecated def bodyAs[T](clazz: Class[T]): T
    @deprecated def setBodyAs[T](clazz: Class[T]): Message
    // ...
  }

They will be removed in 1.0. Instead use

.. code-block:: scala

  package se.scalablesolutions.akka.camel

  case class Message(...) {
    // ...
    def bodyAs[T](implicit m: Manifest[T]): T =
    def setBodyAs[T](implicit m: Manifest[T]): Message
    // ...
  }

Usage example:
.. code-block:: scala

  val m = Message(1.4)
  val b = m.bodyAs[String]

