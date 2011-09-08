ActorRegistry (Java)
====================

Module stability: **SOLID**

ActorRegistry: Finding Actors
-----------------------------

Actors can be looked up using the 'akka.actor.Actors.registry()' object. Through this registry you can look up actors by:

* uuid com.eaio.uuid.UUID – this uses the ``uuid`` field in the Actor class, returns the actor reference for the actor with specified uuid, if one exists, otherwise None
* id string – this uses the ``id`` field in the Actor class, which can be set by the user (default is the class name), returns all actor references to actors with specified id
* parameterized type - returns a ``ActorRef[]`` with all actors that are a subtype of this specific type
* specific actor class - returns a ``ActorRef[]`` with all actors of this exact class

Actors are automatically registered in the ActorRegistry when they are started and removed when they are stopped. But you can explicitly register and unregister ActorRef's if you need to using the ``register`` and ``unregister`` methods.

Here is a summary of the API for finding actors:

.. code-block:: java

  import static akka.actor.Actors.*;
  Option<ActorRef> actor = registry().actorFor(uuid);
  ActorRef[] actors = registry().actors();
  ActorRef[] otherActors = registry().actorsFor(id);
  ActorRef[] moreActors = registry().actorsFor(clazz);

You can shut down all Actors in the system by invoking:

.. code-block:: java

  registry().shutdownAll();

If you want to know when a new Actor is added to or removed from the registry, you can use the subscription API on the registry. You can register an Actor that should be notified when an event happens in the ActorRegistry:

.. code-block:: java

  void addListener(ActorRef listener);
  void removeListener(ActorRef listener);

The messages sent to this Actor are:

.. code-block:: java

  public class ActorRegistered {
    ActorRef actor();
  }
  public class ActorUnregistered {
    ActorRef actor();
  }

So your listener Actor needs to be able to handle these two messages. Example:

.. code-block:: java

  import akka.actor.ActorRegistered;
  import akka.actor.ActorUnregistered;
  import akka.actor.UntypedActor;
  import akka.event.EventHandler;

  public class RegistryListener extends UntypedActor {
    public void onReceive(Object message) throws Exception {
      if (message instanceof ActorRegistered) {
        ActorRegistered event = (ActorRegistered) message;
        EventHandler.info(this, String.format("Actor registered: %s - %s", 
            event.actor().actorClassName(), event.actor().getUuid()));
          event.actor().actorClassName(), event.actor().getUuid()));
      } else if (message instanceof ActorUnregistered) {
        // ...
      }
    }
  }

The above actor can be added as listener of registry events:

.. code-block:: java

  import static akka.actor.Actors.*;

  ActorRef listener = actorOf(RegistryListener.class);
  registry().addListener(listener);
