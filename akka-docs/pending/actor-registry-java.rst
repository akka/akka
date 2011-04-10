ActorRegistry (Java)
====================

Module stability: **SOLID**

ActorRegistry: Finding Actors
-----------------------------

Actors can be looked up using the 'akka.actor.Actors.registry()' object. Through this registry you can look up actors by:
* uuid string – this uses the ‘uuid’ field in the Actor class, returns all actor instances with that uuid
* id string – this uses the ‘id’ field in the Actor class, which can be set by the user (default is the class name), returns instances of a specific Actor
* parameterized type - returns a 'ActorRef[]' with all actors that are a subtype of this specific type
* specific actor class - returns a 'ActorRef[]' with all actors of this exact class

Actors are automatically registered in the ActorRegistry when they are started and removed when they are stopped. But you can explicitly register and unregister ActorRef's if you need to using the 'register' and 'unregister' methods.

Here is a summary of the API for finding actors:

.. code-block:: java

  import static akka.actor.Actors.*;
  Option<ActorRef> actor = registry().actorFor(String uuid);
  Actor[] actors = registry().actors();
  Actor[] otherActors = registry().actorsFor(String id);
  Actor[] moreActors = registry().actorsFor(Class clazz);

You can shut down all Actors in the system by invoking:

.. code-block:: java

  registry().shutdownAll();

If you want to know when a new Actor is added or to or removed from the registry, you can use the subscription API. You can register an Actor that should be notified when an event happens in the ActorRegistry:

.. code-block:: java

  void addListener(ActorRef listener)
  void removeListener(ActorRef listener)

The messages sent to this Actor are:

.. code-block:: java

  class ActorRegistered {
    ActorRef actor();
  }
  class ActorUnregistered {
    ActorRef actor();
  }

So your listener Actor needs to be able to handle these two messages.
