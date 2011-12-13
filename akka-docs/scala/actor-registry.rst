ActorRegistry (Scala)
=====================

Module stability: **SOLID**

ActorRegistry: Finding Actors
-----------------------------

Actors can be looked up by using the ``akka.actor.Actor.registry: akka.actor.ActorRegistry``. Lookups for actors through this registry can be done by:

* uuid akka.actor.Uuid – this uses the ``uuid`` field in the Actor class, returns the actor reference for the actor with specified uuid, if one exists, otherwise None
* id string – this uses the ``id`` field in the Actor class, which can be set by the user (default is the class name), returns all actor references to actors with specified id
* specific actor class - returns an ``Array[Actor]`` with all actors of this exact class
* parameterized type - returns an ``Array[Actor]`` with all actors that are a subtype of this specific type

Actors are automatically registered in the ActorRegistry when they are started, removed or stopped. You can explicitly register and unregister ActorRef's by using the ``register`` and ``unregister`` methods. The ActorRegistry contains many convenience methods for looking up typed actors.

Here is a summary of the API for finding actors:

.. code-block:: scala

  def actors: Array[ActorRef]
  def actorFor(uuid: akka.actor.Uuid): Option[ActorRef]
  def actorsFor(id : String): Array[ActorRef]
  def actorsFor[T <: Actor](implicit manifest: Manifest[T]): Array[ActorRef]
  def actorsFor[T <: Actor](clazz: Class[T]): Array[ActorRef]

  // finding typed actors
  def typedActors: Array[AnyRef]
  def typedActorFor(uuid: akka.actor.Uuid): Option[AnyRef]
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
  def shutdownAll()

If you need to know when a new Actor is added or removed from the registry, you can use the subscription API. You can register an Actor that should be notified when an event happens in the ActorRegistry:

.. code-block:: scala

  def addListener(listener: ActorRef)
  def removeListener(listener: ActorRef)

The messages sent to this Actor are:

.. code-block:: scala

  case class ActorRegistered(@BeanProperty address: String,@BeanProperty actor: ActorRef) extends ActorRegistryEvent
  case class ActorUnregistered(@BeanProperty address: String, @BeanProperty actor: ActorRef) extends ActorRegistryEvent
  case class TypedActorRegistered(@BeanProperty address: String, @BeanProperty actor: ActorRef, @BeanProperty proxy: AnyRef) extends ActorRegistryEvent
  case class TypedActorUnregistered(@BeanProperty address: String, @BeanProperty actor: ActorRef, @BeanProperty proxy: AnyRef) extends ActorRegistryEvent

So your listener Actor needs to be able to handle these messages. Example:

.. code-block:: scala

  import akka.actor._
  import akka.event.EventHandler

  class RegistryListener extends Actor {
    def receive = {
      case event: ActorRegistered =>
        EventHandler.info(this, "Actor registered: %s - %s".format(
          event.actor.actorClassName, event.actor.uuid))
      case event: ActorUnregistered =>
        // ...
    }
  }

The above actor can be added as listener of registry events:

.. code-block:: scala

  import akka.actor._
  import akka.actor.Actor._

  val listener = actorOf(Props[RegistryListener])
  registry.addListener(listener)
