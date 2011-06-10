/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import DeploymentConfig._
import akka.dispatch._
import akka.config._
import Config._
import akka.util.{ ListenerManagement, ReflectiveAccess, Duration, Helpers }
import ReflectiveAccess._
import Helpers.{ narrow, narrowSilently }
import akka.remoteinterface.RemoteSupport
import akka.japi.{ Creator, Procedure }
import akka.AkkaException
import akka.serialization.{ Format, Serializer }
import akka.cluster.ClusterNode
import akka.event.EventHandler
import scala.collection.immutable.Stack

import scala.reflect.BeanProperty

import com.eaio.uuid.UUID

import java.lang.reflect.InvocationTargetException

/**
 * Life-cycle messages for the Actors
 */
sealed trait LifeCycleMessage extends Serializable

/**
 * Marker trait to show which Messages are automatically handled by Akka
 */
sealed trait AutoReceivedMessage { self: LifeCycleMessage ⇒ }

case class HotSwap(code: ActorRef ⇒ Actor.Receive, discardOld: Boolean = true) extends AutoReceivedMessage with LifeCycleMessage {

  /**
   * Java API
   */
  def this(code: akka.japi.Function[ActorRef, Procedure[Any]], discardOld: Boolean) = {
    this((self: ActorRef) ⇒ {
      val behavior = code(self)
      val result: Actor.Receive = { case msg ⇒ behavior(msg) }
      result
    }, discardOld)
  }

  /**
   *  Java API with default non-stacking behavior
   */
  def this(code: akka.japi.Function[ActorRef, Procedure[Any]]) = this(code, true)
}

case object RevertHotSwap extends AutoReceivedMessage with LifeCycleMessage

case class Restart(reason: Throwable) extends AutoReceivedMessage with LifeCycleMessage

case class Exit(dead: ActorRef, killer: Throwable) extends AutoReceivedMessage with LifeCycleMessage

case class Link(child: ActorRef) extends AutoReceivedMessage with LifeCycleMessage

case class Unlink(child: ActorRef) extends AutoReceivedMessage with LifeCycleMessage

case class UnlinkAndStop(child: ActorRef) extends AutoReceivedMessage with LifeCycleMessage

case object PoisonPill extends AutoReceivedMessage with LifeCycleMessage

case object Kill extends AutoReceivedMessage with LifeCycleMessage

case object ReceiveTimeout extends LifeCycleMessage

case class MaximumNumberOfRestartsWithinTimeRangeReached(
  @BeanProperty victim: ActorRef,
  @BeanProperty maxNrOfRetries: Option[Int],
  @BeanProperty withinTimeRange: Option[Int],
  @BeanProperty lastExceptionCausingRestart: Throwable) extends LifeCycleMessage

// Exceptions for Actors
class ActorStartException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause)
class IllegalActorStateException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause)
class ActorKilledException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause)
class ActorInitializationException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause)
class ActorTimeoutException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause)
class InvalidMessageException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause)

/**
 * This message is thrown by default when an Actors behavior doesn't match a message
 */
case class UnhandledMessageException(msg: Any, ref: ActorRef) extends Exception {
  override def getMessage = "Actor %s does not handle [%s]".format(ref, msg)
  override def fillInStackTrace() = this //Don't waste cycles generating stack trace
}

/**
 * Classes for passing status back to the sender.
 */
object Status {
  sealed trait Status extends Serializable
  case object Success extends Status
  case class Failure(cause: Throwable) extends Status
}

/**
 * Actor factory module with factory methods for creating various kinds of Actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Actor extends ListenerManagement {

  private[akka] val TIMEOUT = Duration(config.getInt("akka.actor.timeout", 5), TIME_UNIT).toMillis
  private[akka] val SERIALIZE_MESSAGES = config.getBool("akka.actor.serialize-messages", false)

  /**
   * A Receive is a convenience type that defines actor message behavior currently modeled as
   * a PartialFunction[Any, Unit].
   */
  type Receive = PartialFunction[Any, Unit]

  /**
   * Add shutdown cleanups
   */
  private[akka] lazy val shutdownHook = {
    val hook = new Runnable {
      override def run() {
        // Clear Thread.subclassAudits
        val tf = classOf[java.lang.Thread].getDeclaredField("subclassAudits")
        tf.setAccessible(true)
        val subclassAudits = tf.get(null).asInstanceOf[java.util.Map[_, _]]
        subclassAudits synchronized { subclassAudits.clear() }
      }
    }
    Runtime.getRuntime.addShutdownHook(new Thread(hook, "akka-shutdown-hook"))
    hook
  }

  private[actor] val actorRefInCreation = new ThreadLocal[Stack[ActorRef]] {
    override def initialValue = Stack[ActorRef]()
  }

  /**
   * Handle to the ActorRegistry.
   */
  val registry = new ActorRegistry

  /**
   * Handle to the ClusterNode. API for the cluster client.
   */
  lazy val cluster: ClusterNode = {
    val node = ClusterModule.node
    node.start()
    node
  }

  /**
   * Handle to the RemoteSupport. API for the remote client/server.
   * Only for internal use.
   */
  private[akka] lazy val remote: RemoteSupport = cluster.remoteService

  /**
   * Creates an ActorRef out of the Actor with type T.
   * <pre>
   *   import Actor._
   *   val actor = actorOf[MyActor]
   *   actor.start()
   *   actor ! message
   *   actor.stop()
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf[MyActor].start()
   * </pre>
   */
  def actorOf[T <: Actor: Manifest](address: String): ActorRef =
    actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]], address)

  /**
   * Creates an ActorRef out of the Actor with type T.
   * Uses generated address.
   * <pre>
   *   import Actor._
   *   val actor = actorOf[MyActor]
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf[MyActor].start
   * </pre>
   */
  def actorOf[T <: Actor: Manifest]: ActorRef =
    actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]], new UUID().toString)

  /**
   * Creates an ActorRef out of the Actor of the specified Class.
   * Uses generated address.
   * <pre>
   *   import Actor._
   *   val actor = actorOf(classOf[MyActor])
   *   actor.start()
   *   actor ! message
   *   actor.stop()
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf(classOf[MyActor]).start()
   * </pre>
   */
  def actorOf[T <: Actor](clazz: Class[T]): ActorRef =
    actorOf(clazz, new UUID().toString)

  /**
   * Creates an ActorRef out of the Actor of the specified Class.
   * <pre>
   *   import Actor._
   *   val actor = actorOf(classOf[MyActor])
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf(classOf[MyActor]).start
   * </pre>
   */
  def actorOf[T <: Actor](clazz: Class[T], address: String): ActorRef = {
    createActor(address, () ⇒ newLocalActorRef(clazz, address))
  }

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory function
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * Uses generated address.
   * <p/>
   * <pre>
   *   import Actor._
   *   val actor = actorOf(new MyActor)
   *   actor.start()
   *   actor ! message
   *   actor.stop()
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf(new MyActor).start()
   * </pre>
   */
  def actorOf[T <: Actor](factory: ⇒ T): ActorRef = actorOf(factory, new UUID().toString)

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory function
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * <p/>
   * This function should <b>NOT</b> be used for remote actors.o
   * <pre>
   *   import Actor._
   *   val actor = actorOf(new MyActor)
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf(new MyActor).start
   * </pre>
   */
  def actorOf[T <: Actor](creator: ⇒ T, address: String): ActorRef = {
    createActor(address, () ⇒ new LocalActorRef(() ⇒ creator, address, Transient))
  }

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory (Creator<Actor>)
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * Uses generated address.
   * <p/>
   * JAVA API
   */
  def actorOf[T <: Actor](creator: Creator[T]): ActorRef =
    actorOf(creator, new UUID().toString)

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory (Creator<Actor>)
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * <p/>
   * This function should <b>NOT</b> be used for remote actors.
   * JAVA API
   */
  def actorOf[T <: Actor](creator: Creator[T], address: String): ActorRef = {
    createActor(address, () ⇒ new LocalActorRef(() ⇒ creator.create, address, Transient))
  }

  /**
   * Use to spawn out a block of code in an event-driven actor. Will shut actor down when
   * the block has been executed.
   * <p/>
   * NOTE: If used from within an Actor then has to be qualified with 'Actor.spawn' since
   * there is a method 'spawn[ActorType]' in the Actor trait already.
   * Example:
   * <pre>
   * import Actor.spawn
   *
   * spawn  {
   *   ... // do stuff
   * }
   * </pre>
   */
  def spawn(body: ⇒ Unit)(implicit dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher): Unit = {
    case object Spawn
    actorOf(new Actor() {
      self.dispatcher = dispatcher
      def receive = {
        case Spawn ⇒ try { body } finally { self.stop() }
      }
    }).start() ! Spawn
  }

  /**
   * Implicitly converts the given Option[Any] to a AnyOptionAsTypedOption which offers the method <code>as[T]</code>
   * to convert an Option[Any] to an Option[T].
   */
  implicit def toAnyOptionAsTypedOption(anyOption: Option[Any]) = new AnyOptionAsTypedOption(anyOption)

  /**
   * Implicitly converts the given Future[_] to a AnyOptionAsTypedOption which offers the method <code>as[T]</code>
   * to convert an Option[Any] to an Option[T].
   * This means that the following code is equivalent:
   *   (actor !! "foo").as[Int] (Deprecated)
   *   and
   *   (actor !!! "foo").as[Int] (Recommended)
   */
  implicit def futureToAnyOptionAsTypedOption(anyFuture: Future[_]) = new AnyOptionAsTypedOption({
    try { anyFuture.await } catch { case t: FutureTimeoutException ⇒ }
    anyFuture.resultOrException
  })

  private[akka] def createActor(address: String, actorFactory: () ⇒ ActorRef): ActorRef = {
    Address.validate(address)
    registry.actorFor(address) match { // check if the actor for the address is already in the registry
      case Some(actorRef) ⇒ actorRef // it is -> return it
      case None ⇒ // it is not -> create it
        try {
          Deployer.deploymentFor(address) match {
            case Deploy(_, router, _, Local) ⇒ actorFactory() // create a local actor
            case deploy                      ⇒ newClusterActorRef(actorFactory, address, deploy)
          }
        } catch {
          case e: DeploymentException ⇒
            EventHandler.error(e, this, "Look up deployment for address [%s] falling back to local actor." format address)
            actorFactory() // if deployment fails, fall back to local actors
        }
    }
  }

  private[akka] def newLocalActorRef(clazz: Class[_ <: Actor], address: String): ActorRef = {
    new LocalActorRef(() ⇒ {
      import ReflectiveAccess.{ createInstance, noParams, noArgs }
      createInstance[Actor](clazz.asInstanceOf[Class[_]], noParams, noArgs) match {
        case Right(actor) ⇒ actor
        case Left(exception) ⇒
          val cause = exception match {
            case i: InvocationTargetException ⇒ i.getTargetException
            case _                            ⇒ exception
          }

          throw new ActorInitializationException(
            "Could not instantiate Actor of " + clazz +
              "\nMake sure Actor is NOT defined inside a class/trait," +
              "\nif so put it outside the class/trait, f.e. in a companion object," +
              "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.", cause)
      }
    }, address, Transient)
  }

  private def newClusterActorRef(factory: () ⇒ ActorRef, address: String, deploy: Deploy): ActorRef = {
    deploy match {
      case Deploy(configAdress, router, serializerClassName, Clustered(home, replication: Replication, state: State)) ⇒
        ClusterModule.ensureEnabled()

        if (configAdress != address) throw new IllegalStateException(
          "Deployment config for [" + address + "] is wrong [" + deploy + "]")
        if (!Actor.remote.isRunning) throw new IllegalStateException(
          "Remote server is not running")

        val isHomeNode = DeploymentConfig.isHomeNode(home)
        val replicas = DeploymentConfig.replicaValueFor(replication)

        def storeActorAndGetClusterRef(replicationStrategy: ReplicationStrategy, serializer: Serializer): ActorRef = {
          // add actor to cluster registry (if not already added)
          if (!cluster.isClustered(address)) cluster.store(factory().start(), replicas, replicationStrategy, false, serializer)

          // remote node (not home node), check out as ClusterActorRef
          cluster.ref(address, DeploymentConfig.routerTypeFor(router))
        }

        val serializer = serializerFor(address, serializerClassName)

        state match {
          case _: Stateless | Stateless ⇒
            storeActorAndGetClusterRef(Transient, serializer)

          case Stateful(storage, strategy) ⇒
            if (isHomeNode) { // stateful actor's home node
              cluster
                .use(address, serializer)
                .getOrElse(throw new ConfigurationException(
                  "Could not check out actor [" + address + "] from cluster registry as a \"local\" actor"))
            } else {
              // FIXME later manage different 'storage' (data grid) as well
              storeActorAndGetClusterRef(strategy, serializer)
            }
        }

      case invalid ⇒ throw new IllegalActorStateException(
        "Could not create actor with address [" + address +
          "], not bound to a valid deployment scheme [" + invalid + "]")
    }
  }

  // FIXME move serializerFor method to ...?
  def serializerFor(address: String, serializerClassName: String): Serializer = {
    def serializerErrorDueTo(reason: String) =
      throw new akka.config.ConfigurationException(
        "Could not create Serializer object [" + serializerClassName +
          "] for serialization of actor [" + address +
          "] since " + reason)

    val serializer: Serializer = serializerClassName match {
      case null | "" | Format.`defaultSerializerName` ⇒ Format.Default
      case specialSerializer ⇒
        ReflectiveAccess.getClassFor(specialSerializer) match {
          case Right(clazz) ⇒
            clazz.newInstance match {
              case s: Serializer ⇒ s
              case other         ⇒ serializerErrorDueTo("class must be of type [akka.serialization.Serializer]")
            }
          case Left(exception) ⇒
            val cause = exception match {
              case i: InvocationTargetException ⇒ i.getTargetException
              case _                            ⇒ exception
            }
            serializerErrorDueTo(cause.toString)
        }
    }
    serializer
  }
}

/**
 * Actor base trait that should be extended by or mixed to create an Actor with the semantics of the 'Actor Model':
 * <a href="http://en.wikipedia.org/wiki/Actor_model">http://en.wikipedia.org/wiki/Actor_model</a>
 * <p/>
 * An actor has a well-defined (non-cyclic) life-cycle.
 * <pre>
 * => NEW (newly created actor) - can't receive messages (yet)
 *     => STARTED (when 'start' is invoked) - can receive messages
 *         => SHUT DOWN (when 'exit' is invoked) - can't do anything
 * </pre>
 *
 * <p/>
 * The Actor's API is available in the 'self' member variable.
 *
 * <p/>
 * Here you find functions like:
 *   - !, !!, !!! and forward
 *   - link, unlink, startLink etc
 *   - start, stop
 *   - etc.
 *
 * <p/>
 * Here you also find fields like
 *   - dispatcher = ...
 *   - id = ...
 *   - lifeCycle = ...
 *   - faultHandler = ...
 *   - trapExit = ...
 *   - etc.
 *
 * <p/>
 * This means that to use them you have to prefix them with 'self', like this: <tt>self ! Message</tt>
 *
 * However, for convenience you can import these functions and fields like below, which will allow you do
 * drop the 'self' prefix:
 * <pre>
 * class MyActor extends Actor  {
 *   import self._
 *   id = ...
 *   dispatcher = ...
 *   ...
 * }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Actor {

  /**
   * Type alias because traits cannot have companion objects.
   */
  type Receive = Actor.Receive

  /*
   * Some[ActorRef] representation of the 'self' ActorRef reference.
   * <p/>
   * Mainly for internal use, functions as the implicit sender references when invoking
   * the 'forward' function.
   */
  @transient
  implicit val someSelf: Some[ActorRef] = {
    val refStack = Actor.actorRefInCreation.get
    if (refStack.isEmpty) throw new ActorInitializationException(
      "ActorRef for instance of actor [" + getClass.getName + "] is not in scope." +
        "\n\tYou can not create an instance of an actor explicitly using 'new MyActor'." +
        "\n\tYou have to use one of the factory methods in the 'Actor' object to create a new actor." +
        "\n\tEither use:" +
        "\n\t\t'val actor = Actor.actorOf[MyActor]', or" +
        "\n\t\t'val actor = Actor.actorOf(new MyActor(..))'")

    val ref = refStack.head

    if (ref eq null) throw new ActorInitializationException(
      "Trying to create an instance of an Actor outside of a wrapping 'actorOf'")
    else {
      // Push a null marker so any subsequent calls to new Actor doesn't reuse this actor ref
      Actor.actorRefInCreation.set(refStack.push(null))
      Some(ref)
    }
  }

  /*
   * Option[ActorRef] representation of the 'self' ActorRef reference.
   * <p/>
   * Mainly for internal use, functions as the implicit sender references when invoking
   * one of the message send functions ('!', '!!' and '!!!').
   */
  implicit def optionSelf: Option[ActorRef] = someSelf

  /**
   * The 'self' field holds the ActorRef for this actor.
   * <p/>
   * Can be used to send messages to itself:
   * <pre>
   * self ! message
   * </pre>
   * Here you also find most of the Actor API.
   * <p/>
   * For example fields like:
   * <pre>
   * self.dispatcher = ...
   * self.trapExit = ...
   * self.faultHandler = ...
   * self.lifeCycle = ...
   * self.sender
   * </pre>
   * <p/>
   * Here you also find methods like:
   * <pre>
   * self.reply(..)
   * self.link(..)
   * self.unlink(..)
   * self.start(..)
   * self.stop(..)
   * </pre>
   */
  @transient
  val self: ScalaActorRef = someSelf.get

  /**
   * User overridable callback/setting.
   * <p/>
   * Partial function implementing the actor logic.
   * To be implemented by concrete actor class.
   * <p/>
   * Example code:
   * <pre>
   *   def receive = {
   *     case Ping =&gt;
   *       println("got a 'Ping' message")
   *       self.reply("pong")
   *
   *     case OneWay =&gt;
   *       println("got a 'OneWay' message")
   *
   *     case unknown =&gt;
   *       println("unknown message: " + unknown)
   * }
   * </pre>
   */
  protected def receive: Receive

  /**
   * User overridable callback.
   * <p/>
   * Is called when an Actor is started by invoking 'actor.start()'.
   */
  def preStart() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when 'actor.stop()' is invoked.
   */
  def postStop() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called on a crashed Actor right BEFORE it is restarted to allow clean up of resources before Actor is terminated.
   */
  def preRestart(reason: Throwable) {}

  /**
   * User overridable callback.
   * <p/>
   * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
   */
  def postRestart(reason: Throwable) {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when a message isn't handled by the current behavior of the actor
   * by default it throws an UnhandledMessageException
   */
  def unhandled(msg: Any) {
    throw new UnhandledMessageException(msg, self)
  }

  /**
   * Changes the Actor's behavior to become the new 'Receive' (PartialFunction[Any, Unit]) handler.
   * Puts the behavior on top of the hotswap stack.
   * If "discardOld" is true, an unbecome will be issued prior to pushing the new behavior to the stack
   */
  def become(behavior: Receive, discardOld: Boolean = true) {
    if (discardOld) unbecome()
    self.hotswap = self.hotswap.push(behavior)
  }

  /**
   * Reverts the Actor behavior to the previous one in the hotswap stack.
   */
  def unbecome() {
    val h = self.hotswap
    if (h.nonEmpty) self.hotswap = h.pop
  }

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] final def apply(msg: Any) = {
    if (msg.isInstanceOf[AnyRef] && (msg.asInstanceOf[AnyRef] eq null))
      throw new InvalidMessageException("Message from [" + self.sender + "] to [" + self.toString + "] is null")

    val behaviorStack = self.hotswap

    msg match {
      case l: AutoReceivedMessage ⇒
        autoReceiveMessage(l)

      case msg if behaviorStack.nonEmpty && behaviorStack.head.isDefinedAt(msg) ⇒
        behaviorStack.head.apply(msg)

      case msg if behaviorStack.isEmpty && processingBehavior.isDefinedAt(msg) ⇒
        processingBehavior.apply(msg)

      case unknown ⇒
        unhandled(unknown) //This is the only line that differs from processingbehavior
    }
  }

  private final def autoReceiveMessage(msg: AutoReceivedMessage) {
    msg match {
      case HotSwap(code, discardOld) ⇒ become(code(self), discardOld)
      case RevertHotSwap             ⇒ unbecome()
      case Exit(dead, reason)        ⇒ self.handleTrapExit(dead, reason)
      case Link(child)               ⇒ self.link(child)
      case Unlink(child)             ⇒ self.unlink(child)
      case UnlinkAndStop(child)      ⇒ self.unlink(child); child.stop()
      case Restart(reason)           ⇒ throw reason
      case Kill                      ⇒ throw new ActorKilledException("Kill")
      case PoisonPill ⇒
        val f = self.senderFuture()
        self.stop()
        if (f.isDefined) f.get.completeWithException(new ActorKilledException("PoisonPill"))
    }
  }

  private lazy val processingBehavior = receive //ProcessingBehavior is the original behavior
}

private[actor] class AnyOptionAsTypedOption(anyOption: Option[Any]) {

  /**
   * Convenience helper to cast the given Option of Any to an Option of the given type. Will throw a ClassCastException
   * if the actual type is not assignable from the given one.
   */
  def as[T]: Option[T] = narrow[T](anyOption)

  /**
   * Convenience helper to cast the given Option of Any to an Option of the given type. Will swallow a possible
   * ClassCastException and return None in that case.
   */
  def asSilently[T: Manifest]: Option[T] = narrowSilently[T](anyOption)
}
