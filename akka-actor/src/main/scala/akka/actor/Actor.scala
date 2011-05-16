/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import akka.dispatch._
import akka.config.Config._
import akka.util.{ListenerManagement, ReflectiveAccess, Duration, Helpers}
import Helpers.{narrow, narrowSilently}
import akka.remoteinterface.RemoteSupport
import akka.japi.{Creator, Procedure}
import akka.AkkaException
import akka.serialization._
import akka.event.EventHandler

import scala.reflect.BeanProperty

import com.eaio.uuid.UUID

/**
 * Life-cycle messages for the Actors
 */
sealed trait LifeCycleMessage extends Serializable

/**
 * Marker trait to show which Messages are automatically handled by Akka
 */
sealed trait AutoReceivedMessage { self: LifeCycleMessage => }

case class HotSwap(code: ActorRef => Actor.Receive, discardOld: Boolean = true)
  extends AutoReceivedMessage with LifeCycleMessage {

  /**
   * Java API
   */
  def this(code: akka.japi.Function[ActorRef,Procedure[Any]], discardOld: Boolean) =
    this( (self: ActorRef) => {
      val behavior = code(self)
      val result: Actor.Receive = { case msg => behavior(msg) }
      result
    }, discardOld)

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
  @BeanProperty val victim: ActorRef,
  @BeanProperty val maxNrOfRetries: Option[Int],
  @BeanProperty val withinTimeRange: Option[Int],
  @BeanProperty val lastExceptionCausingRestart: Throwable) extends LifeCycleMessage

// Exceptions for Actors
class ActorStartException          private[akka](message: String) extends AkkaException(message)
class IllegalActorStateException   private[akka](message: String) extends AkkaException(message)
class ActorKilledException         private[akka](message: String) extends AkkaException(message)
class ActorInitializationException private[akka](message: String) extends AkkaException(message)
class ActorTimeoutException        private[akka](message: String) extends AkkaException(message)
class InvalidMessageException      private[akka](message: String) extends AkkaException(message)

/**
 * This message is thrown by default when an Actors behavior doesn't match a message
 */
case class UnhandledMessageException(msg: Any, ref: ActorRef) extends Exception {
  override def getMessage() = "Actor %s does not handle [%s]".format(ref, msg)
  override def fillInStackTrace() = this //Don't waste cycles generating stack trace
}

/**
 * Actor factory module with factory methods for creating various kinds of Actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Actor extends ListenerManagement {

  /**
   * Add shutdown cleanups
   */
  private[akka] lazy val shutdownHook = {
    val hook = new Runnable {
      override def run {
        // Clear Thread.subclassAudits
        val tf = classOf[java.lang.Thread].getDeclaredField("subclassAudits")
        tf.setAccessible(true)
        val subclassAudits = tf.get(null).asInstanceOf[java.util.Map[_,_]]
        subclassAudits synchronized {subclassAudits.clear}
      }
    }
    Runtime.getRuntime.addShutdownHook(new Thread(hook))
    hook
  }

  val registry = new ActorRegistry

  lazy val remote: RemoteSupport = {
    ReflectiveAccess
      .RemoteModule
      .defaultRemoteSupport
      .map(_())
      .getOrElse(throw new UnsupportedOperationException("You need to have akka-remote.jar on classpath"))
  }

  private[akka] val TIMEOUT = Duration(config.getInt("akka.actor.timeout", 5), TIME_UNIT).toMillis
  private[akka] val SERIALIZE_MESSAGES = config.getBool("akka.actor.serialize-messages", false)

  /**
   * A Receive is a convenience type that defines actor message behavior currently modeled as
   * a PartialFunction[Any, Unit].
   */
  type Receive = PartialFunction[Any, Unit]

  private[actor] val actorRefInCreation = new scala.util.DynamicVariable[Option[ActorRef]](None)

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
   def actorOf[T <: Actor : Manifest](address: String): ActorRef =
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
   def actorOf[T <: Actor : Manifest]: ActorRef =
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
    import DeploymentConfig._
    import ReflectiveAccess._
    Address.validate(address)

    try {
      Deployer.deploymentFor(address) match {
        case Deploy(_, router, _, Local) =>
          // FIXME handle 'router' in 'Local' actors
          newLocalActorRef(clazz, address)

        case Deploy(_, router, formatClassName, Clustered(home, replication, state)) =>
          ClusterModule.ensureEnabled()
          if (Actor.remote.isRunning) throw new IllegalStateException("Remote server is not running")

          val hostname = home match {
            case Host(hostname) => hostname
            case IP(address)    => address
            case Node(nodeName) => "localhost" // FIXME lookup hostname for node name
          }

          val replicas = replication match {
            case Replicate(replicas) => replicas
            case AutoReplicate       => -1
            case NoReplicas          => 0
          }

          import ClusterModule.node
          if (hostname == RemoteModule.remoteServerHostname) { // home node for clustered actor

            def formatErrorDueTo(reason: String) = {
              throw new akka.config.ConfigurationException(
                "Could not create Format[T] object [" + formatClassName +
                "] for serialization of actor [" + address +
                "] since " + reason)
            }

            implicit val format: Format[T] = {
              if (formatClassName == "N/A") formatErrorDueTo("no class name defined in configuration")
              val f = ReflectiveAccess.getObjectFor(formatClassName).getOrElse(formatErrorDueTo("it could not be loaded"))
              if (f.isInstanceOf[Format[T]]) f.asInstanceOf[Format[T]]
              else formatErrorDueTo("class must be of type [akka.serialization.Format[T]]")
            }

            if (!node.isClustered(address)) node.store(address, clazz, replicas, false)
            node.use(address)
          } else {
            //val router =
            node.ref(address, null)
          }
          sys.error("Clustered deployment not yet supported")

          /*
          val remoteAddress = Actor.remote.address
          if (remoteAddress.getHostName == hostname && remoteAddress.getPort == port) {
            // home node for actor
          } else {
          }
          */
          /*
            2. Check Home(..)
              a) If home is same as Actor.remote.address then:
                - check if actor is stored in ZK, if not; node.store(..)
                - checkout actor using node.use(..)
              b) If not the same
                - check out actor using node.ref(..)

            Misc stuff:
              - How to define a single ClusterNode to use? Where should it be booted up? How should it be configured?
              - ClusterNode API and Actor.remote API should be made private[akka]
              - Rewrite ClusterSpec or remove it
              - Actor.stop on home node (actor checked out with node.use(..)) should do node.remove(..) of actor
              - Should we allow configuring of session-scoped remote actors? How?


           */

          RemoteActorRef(address, Actor.TIMEOUT, None, ActorType.ScalaActor)

        case invalid => throw new IllegalActorStateException(
          "Could not create actor [" + clazz.getName +
          "] with address [" + address +
          "], not bound to a valid deployment scheme [" + invalid + "]")
      }
    } catch {
      case e: DeploymentException =>
        EventHandler.error(e, this, "Look up deployment for address [%s] falling back to local actor." format address)
        newLocalActorRef(clazz, address) // if deployment fails, fall back to local actors
    }
  }

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory function
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * Uses generated address.
   * <p/>
   * This function should <b>NOT</b> be used for remote actors.
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
  def actorOf[T <: Actor](factory: => T): ActorRef = actorOf(factory, new UUID().toString)

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory function
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * <p/>
   * This function should <b>NOT</b> be used for remote actors.
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
  def actorOf[T <: Actor](factory: => T, address: String): ActorRef = {
    Address.validate(address)
    new LocalActorRef(() => factory, address)
  }

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory (Creator<Actor>)
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * Uses generated address.
   * <p/>
   * This function should <b>NOT</b> be used for remote actors.
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
    Address.validate(address)
    new LocalActorRef(() => creator.create, address)
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
  def spawn(body: => Unit)(implicit dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher): Unit = {
    case object Spawn
    actorOf(new Actor() {
      self.dispatcher = dispatcher
      def receive = {
        case Spawn => try { body } finally { self.stop() }
      }
    }).start() ! Spawn
  }

  private[akka] def newLocalActorRef(clazz: Class[_ <: Actor], address: String): ActorRef = {
    new LocalActorRef(() => {
      import ReflectiveAccess.{ createInstance, noParams, noArgs }
      createInstance[Actor](clazz.asInstanceOf[Class[_]], noParams, noArgs).getOrElse(
        throw new ActorInitializationException(
          "Could not instantiate Actor" +
          "\nMake sure Actor is NOT defined inside a class/trait," +
          "\nif so put it outside the class/trait, f.e. in a companion object," +
          "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'."))
      }, address)
  }

  /**
   * Implicitly converts the given Option[Any] to a AnyOptionAsTypedOption which offers the method <code>as[T]</code>
   * to convert an Option[Any] to an Option[T].
   */
  implicit def toAnyOptionAsTypedOption(anyOption: Option[Any]) = new AnyOptionAsTypedOption(anyOption)
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
  @transient implicit val someSelf: Some[ActorRef] = {
    val optRef = Actor.actorRefInCreation.value
    if (optRef.isEmpty) throw new ActorInitializationException(
      "ActorRef for instance of actor [" + getClass.getName + "] is not in scope." +
      "\n\tYou can not create an instance of an actor explicitly using 'new MyActor'." +
      "\n\tYou have to use one of the factory methods in the 'Actor' object to create a new actor." +
      "\n\tEither use:" +
      "\n\t\t'val actor = Actor.actorOf[MyActor]', or" +
      "\n\t\t'val actor = Actor.actorOf(new MyActor(..))'")
     Actor.actorRefInCreation.value = None
     optRef.asInstanceOf[Some[ActorRef]]
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
  @transient val self: ScalaActorRef = someSelf.get

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
  def preStart {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when 'actor.stop()' is invoked.
   */
  def postStop {}

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
   * Is the actor able to handle the message passed in as arguments?
   */
  def isDefinedAt(message: Any): Boolean = {
    val behaviorStack = self.hotswap
    message match { //Same logic as apply(msg) but without the unhandled catch-all
      case l: AutoReceivedMessage           => true
      case msg if behaviorStack.nonEmpty &&
        behaviorStack.head.isDefinedAt(msg) => true
      case msg if behaviorStack.isEmpty &&
        processingBehavior.isDefinedAt(msg) => true
      case _                                => false
    }
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
  def unbecome(): Unit = {
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
      case l: AutoReceivedMessage           => autoReceiveMessage(l)
      case msg if behaviorStack.nonEmpty &&
        behaviorStack.head.isDefinedAt(msg) => behaviorStack.head.apply(msg)
      case msg if behaviorStack.isEmpty &&
        processingBehavior.isDefinedAt(msg) => processingBehavior.apply(msg)
      case unknown                          => unhandled(unknown) //This is the only line that differs from processingbehavior
    }
  }

  private final def autoReceiveMessage(msg: AutoReceivedMessage): Unit = msg match {
    case HotSwap(code, discardOld) => become(code(self), discardOld)
    case RevertHotSwap             => unbecome()
    case Exit(dead, reason)        => self.handleTrapExit(dead, reason)
    case Link(child)               => self.link(child)
    case Unlink(child)             => self.unlink(child)
    case UnlinkAndStop(child)      => self.unlink(child); child.stop()
    case Restart(reason)           => throw reason
    case Kill                      => throw new ActorKilledException("Kill")
    case PoisonPill                =>
      val f = self.senderFuture
      self.stop()
      if (f.isDefined) f.get.completeWithException(new ActorKilledException("PoisonPill"))
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

/**
 * Marker interface for proxyable actors (such as typed actor).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Proxyable {
  private[actor] def swapProxiedActor(newInstance: Actor)
}

/**
 * Represents the different Actor types.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed trait ActorType
object ActorType {
  case object ScalaActor extends ActorType
  case object TypedActor extends ActorType
}
