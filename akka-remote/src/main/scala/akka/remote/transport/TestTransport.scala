/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.transport

import TestTransport._
import akka.actor._
import akka.remote.transport.AssociationHandle._
import akka.remote.transport.Transport._
import akka.util.ByteString
import com.typesafe.config.Config
import java.util.concurrent.{ CopyOnWriteArrayList, ConcurrentHashMap }
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Transport implementation to be used for testing.
 *
 * The TestTransport is basically a shared memory between actor systems. The TestTransport could be programmed to
 * emulate different failure modes of a Transport implementation. TestTransport keeps a log of the activities it was
 * requested to do. This class is not optimized for performance and MUST not be used as an in-memory transport in
 * production systems.
 */
class TestTransport(
  val localAddress: Address,
  final val registry: AssociationRegistry,
  val maximumPayloadBytes: Int = 32000,
  val schemeIdentifier: String = "test") extends Transport {

  def this(system: ExtendedActorSystem, conf: Config) = {
    this(
      AddressFromURIString(conf.getString("local-address")),
      AssociationRegistry.get(conf.getString("registry-key")),
      conf.getBytes("maximum-payload-bytes").toInt,
      conf.getString("scheme-identifier"))
  }

  import akka.remote.transport.TestTransport._

  override def isResponsibleFor(address: Address): Boolean = true

  private val associationListenerPromise = Promise[AssociationEventListener]()

  private def defaultListen: Future[(Address, Promise[AssociationEventListener])] = {
    registry.registerTransport(this, associationListenerPromise.future)
    Future.successful((localAddress, associationListenerPromise))
  }

  private def defaultAssociate(remoteAddress: Address): Future[AssociationHandle] = {
    registry.transportFor(remoteAddress) match {

      case Some((remoteTransport, remoteListenerFuture)) ⇒
        val (localHandle, remoteHandle) = createHandlePair(remoteTransport, remoteAddress)
        localHandle.writable = false
        remoteHandle.writable = false

        // Pass a non-writable handle to remote first
        remoteListenerFuture flatMap {
          case listener ⇒
            listener notify InboundAssociation(remoteHandle)
            val remoteHandlerFuture = remoteHandle.readHandlerPromise.future

            // Registration of reader at local finishes the registration and enables communication
            for {
              remoteListener ← remoteHandlerFuture
              localListener ← localHandle.readHandlerPromise.future
            } {
              registry.registerListenerPair(localHandle.key, (localListener, remoteListener))
              localHandle.writable = true
              remoteHandle.writable = true
            }

            remoteHandlerFuture.map { _ ⇒ localHandle }
        }

      case None ⇒
        Future.failed(new InvalidAssociationException(s"No registered transport: $remoteAddress", null))
    }
  }

  private def createHandlePair(remoteTransport: TestTransport, remoteAddress: Address): (TestAssociationHandle, TestAssociationHandle) = {
    val localHandle = new TestAssociationHandle(localAddress, remoteAddress, this, inbound = false)
    val remoteHandle = new TestAssociationHandle(remoteAddress, localAddress, remoteTransport, inbound = true)

    (localHandle, remoteHandle)
  }

  private def defaultShutdown: Future[Boolean] = Future.successful(true)

  /**
   * The [[akka.remote.transport.TestTransport.SwitchableLoggedBehavior]] for the listen() method.
   */
  val listenBehavior = new SwitchableLoggedBehavior[Unit, (Address, Promise[AssociationEventListener])](
    (_) ⇒ defaultListen,
    (_) ⇒ registry.logActivity(ListenAttempt(localAddress)))

  /**
   * The [[akka.remote.transport.TestTransport.SwitchableLoggedBehavior]] for the associate() method.
   */
  val associateBehavior = new SwitchableLoggedBehavior[Address, AssociationHandle](
    defaultAssociate _,
    (remoteAddress) ⇒ registry.logActivity(AssociateAttempt(localAddress, remoteAddress)))

  /**
   * The [[akka.remote.transport.TestTransport.SwitchableLoggedBehavior]] for the shutdown() method.
   */
  val shutdownBehavior = new SwitchableLoggedBehavior[Unit, Boolean](
    (_) ⇒ defaultShutdown,
    (_) ⇒ registry.logActivity(ShutdownAttempt(localAddress)))

  override def listen: Future[(Address, Promise[AssociationEventListener])] = listenBehavior(())
  // Need to do like this for binary compatibility reasons
  private[akka] def boundAddress = localAddress
  override def associate(remoteAddress: Address): Future[AssociationHandle] = associateBehavior(remoteAddress)
  override def shutdown(): Future[Boolean] = shutdownBehavior(())

  private def defaultWrite(params: (TestAssociationHandle, ByteString)): Future[Boolean] = {
    registry.getRemoteReadHandlerFor(params._1) match {
      case Some(listener) ⇒
        listener notify InboundPayload(params._2)
        Future.successful(true)
      case None ⇒
        Future.failed(new IllegalStateException("No association present"))
    }
  }

  private def defaultDisassociate(handle: TestAssociationHandle): Future[Unit] = {
    registry.deregisterAssociation(handle.key).foreach {
      registry.remoteListenerRelativeTo(handle, _) notify Disassociated(AssociationHandle.Unknown)
    }
    Future.successful(())
  }

  /**
   * The [[akka.remote.transport.TestTransport.SwitchableLoggedBehavior]] for the write() method on handles. All
   * handle calls pass through this call. Please note, that write operations return a Boolean synchronously, so
   * altering the behavior via pushDelayed will turn write to a blocking operation -- use of pushDelayed therefore
   * is not recommended.
   */
  val writeBehavior = new SwitchableLoggedBehavior[(TestAssociationHandle, ByteString), Boolean](
    defaultBehavior = {
      defaultWrite _
    },
    logCallback = {
      case (handle, payload) ⇒
        registry.logActivity(WriteAttempt(handle.localAddress, handle.remoteAddress, payload))
    })

  /**
   * The [[akka.remote.transport.TestTransport.SwitchableLoggedBehavior]] for the disassociate() method on handles. All
   * handle calls pass through this call.
   */
  val disassociateBehavior = new SwitchableLoggedBehavior[TestAssociationHandle, Unit](
    defaultBehavior = {
      defaultDisassociate _
    },
    logCallback = {
      (handle) ⇒
        registry.logActivity(DisassociateAttempt(handle.localAddress, handle.remoteAddress))
    })

  private[akka] def write(handle: TestAssociationHandle, payload: ByteString): Boolean =
    Await.result(writeBehavior((handle, payload)), 3.seconds)

  private[akka] def disassociate(handle: TestAssociationHandle): Unit = disassociateBehavior(handle)

  override def toString: String = s"TestTransport($localAddress)"

}

object TestTransport {

  type Behavior[A, B] = (A) ⇒ Future[B]

  /**
   * Test utility to make behavior of functions that return some Future[B] controllable from tests. This tool is able
   * to overwrite default behavior with any generic behavior, including failure, and exposes control to the timing of
   * the completion of the returned future.
   *
   * The utility is implemented as a stack of behaviors, where the behavior on the top of the stack represents the
   * currently active behavior. The bottom of the stack always contains the defaultBehavior which can not be popped
   * out.
   *
   * @param defaultBehavior
   *   The original behavior that might be overwritten. It is always possible to restore this behavior
   *
   * @param logCallback
   *   Function that will be called independently of the current active behavior
   *
   * type parameter A:
   *  - Parameter type of the wrapped function. If it takes multiple parameters it must be wrapped in a tuple.
   *
   * type parameter B:
   *  - Type parameter of the future that the original function returns.
   */
  class SwitchableLoggedBehavior[A, B](defaultBehavior: Behavior[A, B], logCallback: (A) ⇒ Unit) extends Behavior[A, B] {

    private val behaviorStack = new CopyOnWriteArrayList[Behavior[A, B]]()
    behaviorStack.add(0, defaultBehavior)

    /**
     * Changes the current behavior to the provided one.
     *
     * @param behavior
     *   Function that takes a parameter type A and returns a Future[B].
     */
    def push(behavior: Behavior[A, B]): Unit = {
      behaviorStack.add(0, behavior)
    }

    /**
     * Changes the behavior to return a completed future with the given constant value.
     *
     * @param c
     *   The constant the future will be completed with.
     */
    def pushConstant(c: B): Unit = push {
      (x) ⇒ Future.successful(c)
    }

    /**
     * Changes the current behavior to return a failed future containing the given Throwable.
     *
     * @param e
     *   The throwable the failed future will contain.
     */
    def pushError(e: Throwable): Unit = push {
      (x) ⇒ Future.failed(e)
    }

    /**
     * Enables control of the completion of the previously active behavior. Wraps the previous behavior in a new
     * one, returns a control promise that starts the original behavior after the control promise is completed.
     *
     * @return
     *   A promise, which delays the completion of the original future until after this promise is completed.
     */
    def pushDelayed: Promise[Unit] = {
      val controlPromise: Promise[Unit] = Promise()
      val originalBehavior = currentBehavior

      push(
        (params: A) ⇒ for (delayed ← controlPromise.future; original ← originalBehavior(params)) yield original)

      controlPromise
    }

    /**
     * Restores the previous behavior.
     */
    def pop(): Unit = {
      if (behaviorStack.size > 1) {
        behaviorStack.remove(0)
      }
    }

    private def currentBehavior = behaviorStack.get(0)

    /**
     * Applies the current behavior, and invokes the callback.
     *
     * @param params
     *   The parameters of this behavior.
     * @return
     *   The result of this behavior wrapped in a future.
     */
    def apply(params: A): Future[B] = {
      logCallback(params)
      currentBehavior(params)
    }
  }

  /**
   * Base trait for activities that are logged by [[akka.remote.transport.TestTransport]].
   */
  sealed trait Activity

  final case class ListenAttempt(boundAddress: Address) extends Activity
  final case class AssociateAttempt(localAddress: Address, remoteAddress: Address) extends Activity
  final case class ShutdownAttempt(boundAddress: Address) extends Activity
  final case class WriteAttempt(sender: Address, recipient: Address, payload: ByteString) extends Activity
  final case class DisassociateAttempt(requester: Address, remote: Address) extends Activity

  /**
   * Shared state among [[akka.remote.transport.TestTransport]] instances. Coordinates the transports and the means
   * of communication between them.
   */
  class AssociationRegistry {

    private val activityLog = new CopyOnWriteArrayList[Activity]()
    private val transportTable = new ConcurrentHashMap[Address, (TestTransport, Future[AssociationEventListener])]()
    private val listenersTable = new ConcurrentHashMap[(Address, Address), (HandleEventListener, HandleEventListener)]()

    /**
     * Returns the remote endpoint for a pair of endpoints relative to the owner of the supplied handle.
     * @param handle the reference handle to determine the remote endpoint relative to
     * @param listenerPair pair of listeners in initiator, receiver order.
     * @return
     */
    def remoteListenerRelativeTo(handle: TestAssociationHandle,
                                 listenerPair: (HandleEventListener, HandleEventListener)): HandleEventListener = {
      listenerPair match {
        case (initiator, receiver) ⇒ if (handle.inbound) initiator else receiver
      }
    }

    /**
     * Logs a transport activity.
     *
     * @param activity Activity to be logged.
     */
    def logActivity(activity: Activity): Unit = {
      activityLog.add(activity)
    }

    /**
     * Takes a thread-safe snapshot of the current state of the activity log.
     *
     * @return Collection containing activities ordered left-to-right according to time (first element is earliest).
     */
    def logSnapshot: Seq[Activity] = {
      var result = List[Activity]()

      val it = activityLog.iterator()
      while (it.hasNext) result ::= it.next()

      result.reverse
    }

    /**
     * Clears the activity log.
     */
    def clearLog(): Unit = {
      activityLog.clear()
    }

    /**
     * Records a mapping between an address and the corresponding (transport, associationEventListener) pair.
     *
     * @param transport
     *   The transport that is to be registered. The address of this transport will be used as key.
     * @param associationEventListenerFuture
     *   The future that will be completed with the listener that will handle the events for the given transport.
     */
    def registerTransport(transport: TestTransport, associationEventListenerFuture: Future[AssociationEventListener]): Unit = {
      transportTable.put(transport.localAddress, (transport, associationEventListenerFuture))
    }

    /**
     * Indicates if all given transports were successfully registered. No associations can be established between
     * transports that are not yet registered.
     *
     * @param addresses
     *   The listen addresses of transports that participate in the test case.
     * @return
     *   True if all transports are successfully registered.
     */
    def transportsReady(addresses: Address*): Boolean = {
      addresses forall {
        transportTable.containsKey(_)
      }
    }

    /**
     * Registers a Future of two handle event listeners corresponding to the two endpoints of an association.
     *
     * @param key
     *   Ordered pair of addresses representing an association. First element must be the address of the initiator.
     * @param listeners
     *   The future containing the listeners that will be responsible for handling the events of the two endpoints of the
     *   association. Elements in the pair must be in the same order as the addresses in the key parameter.
     */
    def registerListenerPair(key: (Address, Address), listeners: (HandleEventListener, HandleEventListener)): Unit = {
      listenersTable.put(key, listeners)
    }

    /**
     * Removes an association.
     * @param key
     *   Ordered pair of addresses representing an association. First element is the address of the initiator.
     * @return
     *   The original entries.
     */
    def deregisterAssociation(key: (Address, Address)): Option[(HandleEventListener, HandleEventListener)] =
      Option(listenersTable.remove(key))

    /**
     * Tests if an association was registered.
     *
     * @param initiatorAddress The initiator of the association.
     * @param remoteAddress The other address of the association.
     *
     * @return True if there is an association for the given addresses.
     */
    def existsAssociation(initiatorAddress: Address, remoteAddress: Address): Boolean = {
      listenersTable.containsKey((initiatorAddress, remoteAddress))
    }

    /**
     * Returns the event handler corresponding to the remote endpoint of the given local handle. In other words
     * it returns the listener that will receive InboundPayload events when {{{write()}}} is called on the given handle.
     *
     * @param localHandle The handle
     * @return The option that contains the Future for the listener if exists.
     */
    def getRemoteReadHandlerFor(localHandle: TestAssociationHandle): Option[HandleEventListener] = {
      Option(listenersTable.get(localHandle.key)) map { remoteListenerRelativeTo(localHandle, _) }
    }

    /**
     * Returns the Transport bound to the given address.
     *
     * @param address The address bound to the transport.
     * @return The transport if exists.
     */
    def transportFor(address: Address): Option[(TestTransport, Future[AssociationEventListener])] =
      Option(transportTable.get(address))

    /**
     * Resets the state of the registry. ''Warning!'' This method is not atomic.
     */
    def reset(): Unit = {
      clearLog()
      transportTable.clear()
      listenersTable.clear()
    }
  }

}

/*
 NOTE: This is a global shared state between different actor systems. The purpose of this class is to allow dynamically
 loaded TestTransports to set up a shared AssociationRegistry. Extensions could not be used for this purpose, as the injection
 of the shared instance must happen during the startup time of the actor system. Association registries are looked
 up via a string key. Until we find a better way to inject an AssociationRegistry to multiple actor systems it is
 strongly recommended to use long, randomly generated strings to key the registry to avoid interference between tests.
*/
object AssociationRegistry {
  private final val registries = scala.collection.mutable.Map[String, AssociationRegistry]()

  def get(key: String): AssociationRegistry = this.synchronized {
    registries.getOrElseUpdate(key, new AssociationRegistry)
  }

  def clear(): Unit = this.synchronized { registries.clear() }
}

final case class TestAssociationHandle(
  localAddress: Address,
  remoteAddress: Address,
  transport: TestTransport,
  inbound: Boolean) extends AssociationHandle {

  @volatile var writable = true

  override val readHandlerPromise: Promise[HandleEventListener] = Promise()

  override def write(payload: ByteString): Boolean =
    if (writable) transport.write(this, payload) else false

  override def disassociate(): Unit = transport.disassociate(this)

  /**
   * Key used in [[akka.remote.transport.TestTransport.AssociationRegistry]] to identify associations. Contains an
   * ordered pair of addresses, where the first element of the pair is always the initiator of the association.
   */
  val key = if (!inbound) (localAddress, remoteAddress) else (remoteAddress, localAddress)
}
