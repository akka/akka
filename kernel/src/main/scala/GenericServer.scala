/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import scala.actors._
import scala.actors.Actor._

import se.scalablesolutions.akka.kernel.config.ScalaConfig._
import se.scalablesolutions.akka.kernel.Helpers._

sealed abstract class GenericServerMessage
case class Init(config: AnyRef) extends GenericServerMessage
case class ReInit(config: AnyRef) extends GenericServerMessage
case class Shutdown(reason: AnyRef) extends GenericServerMessage
case class Terminate(reason: AnyRef) extends GenericServerMessage
case class HotSwap(code: Option[PartialFunction[Any, Unit]]) extends GenericServerMessage

/**
 * Base trait for all user-defined servers/actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait GenericServer extends Actor {
  private val actorScheduler = new ManagedActorScheduler

  /**
   * Template method implementing the server logic.
   * To be implemented by subclassing server.
   * <p/>
   * Example code:
   * <pre>
   *   override def body: PartialFunction[Any, Unit] = {
   *     case Ping =>
   *       println("got a ping")
   *       reply("pong")
   *
   *     case OneWay =>
   *       println("got a oneway")
   *   }
   * </pre>
   */
  def body: PartialFunction[Any, Unit]

  /**
   * Callback method that is called during initialization.
   * To be implemented by subclassing server.
   */
  def init(config: AnyRef) {}

  /**
   * Callback method that is called during reinitialization after a server crash.
   * To be implemented by subclassing server.
   */
  def reinit(config: AnyRef) {}

  /**
   * Callback method that is called during termination.
   * To be implemented by subclassing server.
   */
  def shutdown(reason: AnyRef) {}

  def act = loop { react { genericBase orElse actorBase } }

  //override protected def scheduler = actorScheduler
  
  private def actorBase: PartialFunction[Any, Unit] = hotswap getOrElse body

  private var hotswap: Option[PartialFunction[Any, Unit]] = None

  private val genericBase: PartialFunction[Any, Unit] = {
    case Init(config) =>      init(config)
    case ReInit(config) =>    reinit(config)
    case HotSwap(code) =>     hotswap = code
    case Shutdown(reason) =>  shutdown(reason); reply('success)
    case Terminate(reason) => exit(reason)
  }
}

/**
 * The container (proxy) for GenericServer, responsible for managing the life-cycle of the server;
 * such as shutdown, restart, re-initialization etc.
 * Each GenericServerContainer manages one GenericServer.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class GenericServerContainer(
  val id: String,
  private[kernel] var serverFactory: () => GenericServer) extends Logging {
  require(id != null && id != "")

  private[kernel] var lifeCycle: Option[LifeCycle] = None
  private[kernel] val lock = new ReadWriteLock
  private[kernel] val txItemsLock = new ReadWriteLock
  private[kernel] val serializer = new JavaSerializationSerializer

  private var server: GenericServer = _
  private var currentConfig: Option[AnyRef] = None
  private[kernel] var timeout = 5000

  // TODO: see if we can parameterize class and add type safe getActor method
  //class GenericServerContainer[T <: GenericServer](var factory: () => T) {
  //def getActor: T = server

  private[kernel] def transactionalItems: List[Transactional] = txItemsLock.withReadLock {
    _transactionalMaps ::: _transactionalVectors ::: _transactionalRefs
  }
  
  // TX Maps
  private[kernel] var _transactionalMaps: List[TransactionalMap[_, _]] = Nil
  private[kernel] def transactionalMaps_=(maps: List[TransactionalMap[_, _]]) = txItemsLock.withWriteLock {
    _transactionalMaps = maps
  }
  private[kernel] def transactionalMaps: List[TransactionalMap[_, _]] = txItemsLock.withReadLock {
    _transactionalMaps
  }

  // TX Vectors
  private[kernel] var _transactionalVectors: List[TransactionalVector[_]] = Nil
  private[kernel] def transactionalVectors_=(vectors: List[TransactionalVector[_]]) = txItemsLock.withWriteLock {
    _transactionalVectors = vectors
  }
  private[kernel] def transactionalVectors: List[TransactionalVector[_]] = txItemsLock.withReadLock {
    _transactionalVectors
  }

  // TX Refs
  private[kernel] var _transactionalRefs: List[TransactionalRef[_]] = Nil
  private[kernel] def transactionalRefs_=(refs: List[TransactionalRef[_]]) = txItemsLock.withWriteLock {
    _transactionalRefs = refs
  }
  private[kernel] def transactionalRefs: List[TransactionalRef[_]] = txItemsLock.withReadLock {
    _transactionalRefs
  }

  /**
   * Sends a one way message to the server - alias for <code>cast(message)</code>.
   * <p>
   * Example:
   * <pre>
   *   server ! Message
   * </pre>
   */
  def !(message: Any) = {
    require(server != null)
    lock.withReadLock { server ! message }
  }

  /**
   * Sends a message to the server returns a FutureWithTimeout holding the future reply .
   * <p>
   * Example:
   * <pre>
   *  val future = server !! Message
   *  future.receiveWithin(100) match {
   *    case None => ... // timed out
   *    case Some(reply) => ... // handle reply
   *  }
   * </pre>
   */
  def !![T](message: Any): FutureWithTimeout[T] = {
    require(server != null)
    lock.withReadLock { server !!! message }
  }

  /**
   * Sends a message to the server and blocks indefinitely (no time out), waiting for the reply.
   * <p>
   * Example:
   * <pre>
   *   val result: String = server !? Message
   * </pre>
   */
  def !?[T](message: Any): T = {
    require(server != null)
    val future: Future[T] = lock.withReadLock { server.!![T](message, {case t => t.asInstanceOf[T]}) }
    Actor.receive {
      case (future.ch ! arg) => arg.asInstanceOf[T]
    }
  }

  /**
   * Sends a message to the server and gets a future back with the reply. Returns
   * an Option with either Some(result) if succesful or None if timeout.
   * <p>
   * Timeout specified by the <code>setTimeout(time: Int)</code> method.
   * <p>
   * Example:
   * <pre>
   *   (server !!! Message).getOrElse(throw new RuntimeException("time out")
   * </pre>
   */
  def !!![T](message: Any): Option[T] = {
    require(server != null)
    val future: FutureWithTimeout[T] = lock.withReadLock { server !!! message }
    future.receiveWithin(timeout)
  }

  /**
   * Sends a message to the server and gets a future back with the reply.
   * <p>
   * Tries to get the reply within the timeout specified in the GenericServerContainer
   * and else execute the error handler (which can return a default value, throw an exception
   * or whatever is appropriate).
   * <p>
   * Example:
   * <pre>
   *   server !!! (Message, throw new RuntimeException("time out"))
   *   // OR
   *   server !!! (Message, DefaultReturnValue)
   * </pre>
   */
  def !!![T](message: Any, errorHandler: => T): T = !!!(message, errorHandler, timeout)

  /**
   * Sends a message to the server and gets a future back with the reply.
   * <p>
   * Tries to get the reply within the timeout specified as parameter to the method
   * and else execute the error handler (which can return a default value, throw an exception
   * or whatever is appropriate).
   * <p>
   * Example:
   * <pre>
   *   server !!! (Message, throw new RuntimeException("time out"), 1000)
   *   // OR
   *   server !!! (Message, DefaultReturnValue, 1000)
   * </pre>
   */
  def !!![T](message: Any, errorHandler: => T, time: Int): T = {
    require(server != null)
    println("---------- SERVER " + server + " MESSAGE " + message)
    val future: FutureWithTimeout[T] = lock.withReadLock { server !!! message }
    future.receiveWithin(time) match {
      case None => errorHandler
      case Some(reply) => reply
    }
  }

  /**
   * Initializes the server by sending a Init(config) message.
   */
  def init(config: AnyRef) = lock.withWriteLock {
    currentConfig = Some(config)
    server ! Init(config)
  }

  /**
   * Re-initializes the server by sending a ReInit(config) message with the most recent configuration.
   */
  def reinit = lock.withWriteLock {
    currentConfig match {
      case Some(config) => server ! ReInit(config)
      case None => {}
    }
  }

  /**
   * Hotswaps the server body by sending it a HotSwap(code) with the new code
   * block (PartialFunction) to be executed.
   */
  def hotswap(code: Option[PartialFunction[Any, Unit]]) = lock.withReadLock { server ! HotSwap(code) }

  /**
   * Swaps the server factory, enabling creating of a completely new server implementation
   * (upon failure and restart).
   */
  def swapFactory(newFactory: () => GenericServer) = serverFactory = newFactory

  /**
   * Sets the timeout for the call(..) method, e.g. the maximum time to wait for a reply
   * before bailing out. Sets the timeout on the future return from the call to the server.
   */
  def setTimeout(time: Int) = timeout = time

  /**
   * Returns the next message in the servers mailbox.
   */
  def nextMessage = lock.withReadLock { server ? }

  /**
   * Creates a new actor for the GenericServerContainer, and return the newly created actor.
   */
  private[kernel] def newServer(): GenericServer = lock.withWriteLock {
    server = serverFactory()
    server
  }

  /**
   * Starts the server.
   */
  private[kernel] def start = lock.withReadLock { server.start }

  /**
   * Terminates the server with a reason by sending a Terminate(Some(reason)) message.
   */
  private[kernel] def terminate(reason: AnyRef) = lock.withReadLock { server ! Terminate(reason) }

  /**
   * Terminates the server with a reason by sending a Terminate(Some(reason)) message,
   * the shutdownTime defines the maximal time to wait for the server to shutdown before
   * killing it.
   */
  private[kernel] def terminate(reason: AnyRef, shutdownTime: Int) = lock.withReadLock {
    if (shutdownTime > 0) {
      log.debug("Waiting [%s milliseconds for the server to shut down before killing it.", shutdownTime)
      server !? (shutdownTime, Shutdown(reason)) match {
        case Some('success) => log.debug("Server [%s] has been shut down cleanly.", id)
        case None => log.warning("Server [%s] was **not able** to complete shutdown cleanly within its configured shutdown time [%s]", id, shutdownTime)
      }
    }
    server ! Terminate(reason)
  }

  private[kernel] def reconfigure(reason: AnyRef, restartedServer: GenericServer, supervisor: Supervisor) = lock.withWriteLock {
    server = restartedServer
    reinit
  }

  private[kernel] def getServer: GenericServer = server

  private[kernel] def cloneServerAndReturnOldVersion: GenericServer = lock.withWriteLock {
    val oldServer = server
    server = serializer.deepClone(server)
    oldServer
  }
  
  private[kernel] def swapServer(newServer: GenericServer) = lock.withWriteLock {
    server = newServer
  }

  override def toString(): String = "GenericServerContainer[" + server + "]"
}

