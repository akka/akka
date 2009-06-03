/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import kernel.config.ScalaConfig._
import kernel.Helpers._
import kernel.reactor._
import scala.collection.mutable.HashSet

sealed abstract class GenericServerMessage
case class Init(config: AnyRef) extends GenericServerMessage
case class ReInit(config: AnyRef) extends GenericServerMessage
case class Shutdown(reason: AnyRef) extends GenericServerMessage
case class Terminate(reason: AnyRef) extends GenericServerMessage
case class HotSwap(code: Option[PartialFunction[Any, Unit]]) extends GenericServerMessage
case class Killed(victim: GenericServer, reason: AnyRef) extends GenericServerMessage

/**
 * Base trait for all user-defined servers.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait GenericServer {
  private val unit = TimeUnit.MILLISECONDS
  private[kernel] var sender: GenericServerContainer = _
  private[kernel] var container: GenericServerContainer = _
  private[kernel] var mailbox: MessageQueue = _
  @volatile protected[this] var isRunning = true
  protected var trapExit: Boolean = false

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
  protected def body: PartialFunction[Any, Unit]

  /**
   * Callback method that is called during initialization.
   * To be implemented by subclassing server.
   */
  protected def init(config: AnyRef) {}

  /**
   * Callback method that is called during reinitialization after a server crash.
   * To be implemented by subclassing server.
   */
  protected def reinit(config: AnyRef) {}

  /**
   * Callback method that is called during termination.
   * To be implemented by subclassing server.
   */
  protected def shutdown(reason: AnyRef) {}

  protected def reply(message: AnyRef) = sender ! message
  
  private[kernel] def link(server: GenericServer) = container.link(server)

  private[kernel] def unlink(server: GenericServer) = container.unlink(server)

  private[kernel] def !(message: AnyRef) = mailbox.put(new MessageHandle(this, message, new NullFutureResult))

  private[kernel] def !![T](message: AnyRef)(implicit timeout: Long): Option[T] = {
    val future = new GenericFutureResult(unit.toNanos(timeout))
    mailbox.put(new MessageHandle(this, message, future))
    future.await
    val result = future.result
    if (result == null) None
    else Some(result.asInstanceOf[T])
  }

  private[kernel] def !?(message: AnyRef): AnyRef = {
    val future = new GenericFutureResult(unit.toNanos(100000))
    mailbox.put(new MessageHandle(this, message, future))
    future.await
    future.result.asInstanceOf[AnyRef]
  }

  private[kernel] def start = {} //try { act } catch { case e: SuspendServerException => act }

  private[kernel] def shutdown(shutdownTime: Int, reason: AnyRef) {
    //FIXME: how to implement?
  }

  private[kernel] def handle(message: AnyRef, future: CompletableFutureResult) = {
    react(message) { lifecycle orElse base }
/*
    try {
      val result = message.asInstanceOf[Invocation].joinpoint.proceed
      future.completeWithResult(result)
    } catch {
      case e: Exception => future.completeWithException(e)
    }
*/
  }

  //private[kernel] def act = while(isRunning) { react { lifecycle orElse base } }

  private[this] def base: PartialFunction[Any, Unit] = hotswap getOrElse body

  private var hotswap: Option[PartialFunction[Any, Unit]] = None

  private val lifecycle: PartialFunction[Any, Unit] = {
    case Init(config) =>      init(config)
    case ReInit(config) =>    reinit(config)
    case HotSwap(code) =>     hotswap = code
    case Terminate(reason) => shutdown(reason); exit
  }

  protected[this] def react(message: AnyRef)(f: PartialFunction[AnyRef, Unit]): Unit = {
    // FIXME: loop over all elements, grab the first that matches
    if (f.isDefinedAt(message)) {
 //     sender = message.sender.asInstanceOf[GenericServerContainer]
      f(message)
    } else {
      throw new IllegalArgumentException("Message not defined: " + message)
    }
    //throw new SuspendServerException
  }
  
  private[this] def exit = isRunning = false  
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
  private[this] var serverFactory: () => GenericServer) extends Logging {
  require(id != null && id != "")

  private[this] val txItemsLock = new ReadWriteLock
  private[this] val serializer = new JavaSerializationSerializer
  private[this] val linkedServers = new HashSet[GenericServer]
  private[kernel] var server: GenericServer = serverFactory()
  private[this] var currentConfig: Option[AnyRef] = None

  private[kernel] val lock = new ReadWriteLock
  private[kernel] var lifeCycle: Option[LifeCycle] = None
  private[kernel] implicit var timeout = 5000L

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

  def link(server: GenericServer) = lock.withWriteLock { linkedServers + server }

  def unlink(server: GenericServer) = lock.withWriteLock { linkedServers - server }

  /**
   * Sends a one way message to the server.
   * <p>
   * Example:
   * <pre>
   *   server ! Message
   * </pre>
   */
  def !(message: AnyRef) = {
    require(server != null)
    lock.withReadLock { server ! message }
  }

  /**
   * Sends a message to the server and gets a future back with the reply. Returns
   * an Option with either Some(result) if succesful or None if timeout.
   * <p>
   * Timeout specified by the <code>setTimeout(time: Int)</code> method.
   * <p>
   * Example:
   * <pre>
   *   (server !! Message).getOrElse(throw new RuntimeException("time out")
   * </pre>
   */
  def !![T](message: AnyRef): Option[T] = {
    require(server != null)
    lock.withReadLock { server !! message }
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
   *   server !! (Message, throw new RuntimeException("time out"))
   *   // OR
   *   server !! (Message, DefaultReturnValue)
   * </pre>
   */
  def !![T](message: AnyRef, errorHandler: => T): T = {
    require(server != null)
    lock.withReadLock { (server !! message).getOrElse(errorHandler).asInstanceOf[T] }
  }
  
  /**
   * Sends a message to the server and gets a future back with the reply.
   * <p>
   * Tries to get the reply within the timeout specified as parameter to the method
   * and else execute the error handler (which can return a default value, throw an exception
   * or whatever is appropriate).
   * <p>
   * Example:
   * <pre>
   *   server !! (Message, throw new RuntimeException("time out"), 1000)
   *   // OR
   *   server !! (Message, DefaultReturnValue, 1000)
   * </pre>
   */
  def !![T](message: AnyRef, errorHandler: => T, time: Long): T = {
    require(server != null)
    lock.withReadLock { (server.!!(message)(time)).getOrElse(errorHandler).asInstanceOf[T] }
  }
  
  /**
   * Sends a synchronous message to the server.
   * <p>
   * Example:
   * <pre>
   *   val result = server !? Message
   * </pre>
   */
  def !?(message: AnyRef) = {
    require(server != null)
    lock.withReadLock { server !? message }
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
   * Creates a new actor for the GenericServerContainer, and return the newly created actor.
   */
  private[kernel] def newServer(): GenericServer = lock.withWriteLock {
    server = serverFactory()
    server.container = this
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
      server.shutdown(shutdownTime, Shutdown(reason))
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
    server.container = this
  }

  override def toString(): String = "GenericServerContainer[" + server + "]"
}

private[kernel] class SuspendServerException extends Throwable {
  override def fillInStackTrace(): Throwable = this
}