ActorRegistry (Scala)
=====================

Module stability: **SOLID**

ActorRegistry: Finding Actors
-----------------------------

Actors can be looked up by using the **akka.actor.Actor.registry: akka.actor.ActorRegistry**. Lookups for actors through this registry can be done by:
* uuid string – this uses the ‘**uuid**’ field in the Actor class, returns all actor instances with the specified uuid
* id string – this uses the ‘**id**’ field in the Actor class, which can be set by the user (default is the class name), returns instances of a specific Actor
* specific actor class - returns an '**Array[Actor]**' with all actors of this exact class
* parameterized type - returns an '**Array[Actor]**' with all actors that are a subtype of this specific type

Actors are automatically registered in the ActorRegistry when they are started, removed or stopped. You can explicitly register and unregister ActorRef's by using the '**register**' and '**unregister**' methods. The ActorRegistry contains many convenience methods for looking up typed actors.

Here is a summary of the API for finding actors:

.. code-block:: scala

  def actors: Array[ActorRef]
  def actorFor(uuid: String): Option[ActorRef]
  def actorsFor(id : String): Array[ActorRef]
  def actorsFor[T <: Actor](implicit manifest: Manifest[T]): Array[ActorRef]
  def actorsFor[T <: Actor](clazz: Class[T]): Array[ActorRef]

  // finding typed actors
  def typedActors: Array[AnyRef]
  def typedActorFor(uuid: Uuid): Option[AnyRef]
  def typedActorsFor(id: String): Array[AnyRef]
  def typedActorsFor[T <: AnyRef](implicit manifest: Manifest[T]): Array[AnyRef]
  def typedActorsFor[T <: AnyRef](clazz: Class[T]): Array[AnyRef]

Examples of how to use them:

.. code-block:: scala

  val actor =  Actor.registry.actorFor(uuid)
  val pojo  =  Actor.registry.typedActorFor(uuid)

.. code-block:: scala

  val actors = Actor.registry.actorsFor(classOf[...])
  val pojos  = Actor.registry.typedActorsFor(classOf[...])

.. code-block:: scala

  val actors =  Actor.registry.actorsFor(id)
  val pojos  =  Actor.registry.typedActorsFor(id)

.. code-block:: scala

  val actors = Actor.registry.actorsFor[MyActorType]
  val pojos  = Actor.registry.typedActorsFor[MyTypedActorImpl]

The ActorRegistry also has a 'shutdownAll' and 'foreach' methods:

.. code-block:: scala

  def foreach(f: (ActorRef) => Unit)
  def foreachTypedActor(f: (AnyRef) => Unit)
  def shutdownAll

If you need to know when a new Actor is added or removed from the registry, you can use the subscription API. You can register an Actor that should be notified when an event happens in the ActorRegistry:

.. code-block:: scala

  def addListener(listener: ActorRef)
  def removeListener(listener: ActorRef)

The messages sent to this Actor are:

.. code-block:: scala

  case class ActorRegistered(actor: ActorRef)
  case class ActorUnregistered(actor: ActorRef)

So your listener Actor needs to be able to handle these two messages.
