package akka.remote.transport

import akka.actor.{ ActorRef, Address }
import scala.concurrent.{ Promise, Future }
import akka.remote.transport.Transport._
import akka.remote.transport.TestTransport.AssociationRegistry
import akka.remote.transport.AssociationHandle.{ Disassociated, InboundPayload }
import akka.util.ByteString

import java.util.concurrent.{ CopyOnWriteArrayList, ConcurrentHashMap }

// Default EC is used, but this is just a test utility -- please forgive...

import scala.concurrent.ExecutionContext.Implicits.global

object TestTransport {

  type Behavior[A, B] = (A) ⇒ Future[B]

  /**
   * Test utility to make behavior of functions that return some Future[B] controllable from tests. This tool is able
   * to overwrite default behavior with any generic behavior, including failure, and allows to control the timing of
   * the completition of the returned future.
   * @param defaultBehavior
   * The original behavior that might be overwritten. It is always possible to restore this behavior
   * @param logCallback
   * Function that will be called independently of the current active behavior
   * @tparam A
   * Parameter type of the wrapped function. If it takes multiple parameters it must be wrapped in a tuple.
   * @tparam B
   * Type parameter of the future that the original function returns.
   */
  class SwitchableLoggedBehavior[A, B](defaultBehavior: Behavior[A, B], logCallback: (A) ⇒ Unit) extends Behavior[A, B] {

    private val behaviorStack = new CopyOnWriteArrayList[Behavior[A, B]]()
    behaviorStack.add(0, defaultBehavior)

    /**
     * Changes the behavior to a provided one.
     * @param behavior
     * Function that takes a parameter type A and returns a Future[B].
     */
    def push(behavior: Behavior[A, B]): Unit = {
      behaviorStack.add(0, behavior)
    }

    /**
     * Changes the behavior to return a completed future with the given constant value.
     * @param c
     * The constant the future will be completed with.
     */
    def pushConstant(c: B): Unit = push {
      (x) ⇒ Promise.successful(c).future
    }

    /**
     * Changes the behavior to retur a failed future with the given Throwable.
     * @param e
     * The throwable the failed future will contain.
     */
    def pushError(e: Throwable): Unit = push {
      (x) ⇒ Promise.failed(e).future
    }

    /**
     * Allows total control of the completion of the previously active behavior. Wraps the previous behavior in a new
     * one, returns a control promise and completes the original future when the control promise is completed.
     * @return
     * A promise, which delays the completion of the original future until this promise is completed.
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
     * Parameters needed for this behavior.
     * @return
     * The result of this behavior wrapped in a future.
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

  case class ListenAttempt(boundAddress: Address) extends Activity

  case class AssociateAttempt(localAddress: Address, remoteAddress: Address) extends Activity

  case class ShutdownAttempt(boundAddress: Address) extends Activity

  case class WriteAttempt(sender: Address, recipient: Address, payload: ByteString) extends Activity

  case class DisassociateAttempt(requester: Address, remote: Address) extends Activity

  /**
   * Shared state among [[akka.remote.transport.TestTransport]] instances. Coordinates the transports and the means
   * of communication between them.
   */
  class AssociationRegistry {

    private val activityLog = new CopyOnWriteArrayList[Activity]()
    private val transportTable = new ConcurrentHashMap[Address, (TestTransport, ActorRef)]()
    private val handlersTable = new ConcurrentHashMap[(Address, Address), (ActorRef, ActorRef)]()

    /**
     * Logs a transport activity.
     * @param activity Activity to be logged.s
     */
    def logActivity(activity: Activity): Unit = {
      activityLog.add(activity)
    }

    /**
     * Takes a thread-safe snapshot of the current state of the activity log.
     * @return Collection containing activities ordered left-to-right according to time (first element is earliest).
     */
    def logSnapshot: Seq[Activity] = {
      var result = List[Activity]()

      val it = activityLog.iterator()
      while (it.hasNext) result ::= it.next()

      result.reverse
    }

    def clearLog(): Unit = {
      activityLog.clear()
    }

    /**
     * Records a mapping between an address and the corresponding (transport, actor) pair.
     * @param transport
     * The transport that is to be registered. The address of this transport will be used as key.
     * @param responsibleActor
     * The actor that will handle the events for the given transport.
     */
    def registerTransport(transport: TestTransport, responsibleActor: ActorRef): Unit = {
      transportTable.put(transport.localAddress, (transport, responsibleActor))
    }

    /**
     * Indicates if all given transports were successfully registered. No associations can be established between
     * transports that are not yet registered.
     * @param transports The transports that participate in the test case.
     * @return True if all transports are successfully registered.
     */
    def transportsReady(transports: TestTransport*): Boolean = {
      transports forall {
        t ⇒ transportTable.containsKey(t.localAddress)
      }
    }

    /**
     * Registers the two actors corresponding to the two endpoints of an association.
     * @param key
     * Ordered pair of addresses representing an association. First element is the address of the initiator.
     * @param readHandlers
     * The actors that will be responsible for handling the events of the two endpoints of the association. Ordering
     * is the same as with the key parameter.
     */
    def registerHandlePair(key: (Address, Address), readHandlers: (ActorRef, ActorRef)): Unit = {
      handlersTable.put(key, readHandlers)
    }

    /**
     * Removes an association.
     * @param key
     * Ordered pair of addresses representing an association. First element is the address of the initiator.
     * @return
     * The original entries.
     */
    def deregisterAssociation(key: (Address, Address)): Option[(ActorRef, ActorRef)] = Option(handlersTable.remove(key))

    /**
     * Tests if an association was registered.
     * @param initiatorAddress The initiator of the association.
     * @param remoteAddress The other address of the association.
     * @return True if there is an association for the given addresses.
     */
    def existsAssociation(initiatorAddress: Address, remoteAddress: Address): Boolean = {
      handlersTable.containsKey((initiatorAddress, remoteAddress))
    }

    /**
     * Returns the event handler actor corresponding to the remote endpoint of the given local handle. In other words
     * it returns the actor that will receive InboundPayload events when {{{write()}}} is called on the given handle.
     * @param localHandle The handle
     * @return The option that contanis the handler actor if exists.
     */
    def getRemoteReadHandlerFor(localHandle: TestAssociationHandle): Option[ActorRef] = {
      Option(handlersTable.get(localHandle.key)) map {
        case (handler1, handler2) ⇒ if (localHandle.inbound) handler2 else handler1
      }
    }

    /**
     * Returns the Transport bound to the given address.
     * @param address The address bound to the transport.
     * @return The transport if exists.
     */
    def transportFor(address: Address): Option[(TestTransport, ActorRef)] = Option(transportTable.get(address))

    /**
     * Resets the state of the registry. ''Beware!'' This method is not atomic.
     */
    def reset(): Unit = {
      clearLog()
      transportTable.clear()
      handlersTable.clear()
    }
  }

}

/**
 * Transport implementation to be used for testing.
 *
 * The TestTransport is basically a shared memory between actor systems. The TestTransport could be programmed to
 * emulate different failure modes of a Transport implementation. It also keeps a log of the transport activities.
 */
class TestTransport(
  val localAddress: Address,
  val registry: AssociationRegistry,
  val maximumPayloadBytes: Int = 32000) extends Transport {

  import akka.remote.transport.TestTransport._

  private val actorPromise = Promise[ActorRef]()

  private def defaultListen: Future[(Address, Promise[ActorRef])] = {
    actorPromise.future.onSuccess {
      case actorRef: ActorRef ⇒ registry.registerTransport(this, actorRef)
    }
    Promise.successful((localAddress, actorPromise)).future
  }

  private def defaultAssociate(remoteAddress: Address): Future[Status] = {
    registry.transportFor(remoteAddress) match {

      case Some((remoteTransport, actor)) ⇒
        val (localHandle, remoteHandle) = createHandlePair(remoteTransport, remoteAddress)
        actor ! InboundAssociation(remoteHandle)

        // Register pairs of actor after both handles were initialized
        val bothSidesOpen: Future[(ActorRef, ActorRef)] = for (
          actor1 ← localHandle.readHandlerPromise.future;
          actor2 ← remoteHandle.readHandlerPromise.future
        ) yield (actor1, actor2)

        bothSidesOpen.onSuccess {
          case (actor1, actor2) ⇒ registry.registerHandlePair(localHandle.key, (actor1, actor2))
        }

        Promise.successful(Ready(localHandle)).future

      case None ⇒ Promise.successful(Fail).future
    }
  }

  private def createHandlePair(remoteTransport: TestTransport, remoteAddress: Address): (TestAssociationHandle, TestAssociationHandle) = {
    val localHandle = new TestAssociationHandle(localAddress, remoteAddress, this, inbound = false)
    val remoteHandle = new TestAssociationHandle(remoteAddress, localAddress, remoteTransport, inbound = true)

    (localHandle, remoteHandle)
  }

  private def defaultShutdown: Future[Unit] = Promise.successful(()).future

  /**
   * The [[akka.remote.transport.TestTransport.SwitchableLoggedBehavior]] for the listen() method.
   */
  val listenBehavior = new SwitchableLoggedBehavior[Unit, (Address, Promise[ActorRef])](
    (_) ⇒ defaultListen,
    (_) ⇒ registry.logActivity(ListenAttempt(localAddress)))

  /**
   * The [[akka.remote.transport.TestTransport.SwitchableLoggedBehavior]] for the associate() method.
   */
  val associateBehavior = new SwitchableLoggedBehavior[Address, Status](
    defaultAssociate _,
    (remoteAddress) ⇒ registry.logActivity(AssociateAttempt(localAddress, remoteAddress)))

  /**
   * The [[akka.remote.transport.TestTransport.SwitchableLoggedBehavior]] for the shutdown() method.
   */
  val shutdownBehavior = new SwitchableLoggedBehavior[Unit, Unit](
    (_) ⇒ defaultShutdown,
    (_) ⇒ registry.logActivity(ShutdownAttempt(localAddress)))

  override def listen: Future[(Address, Promise[ActorRef])] = listenBehavior()
  override def associate(remoteAddress: Address): Future[Status] = associateBehavior(remoteAddress)
  override def shutdown(): Future[Unit] = shutdownBehavior()

  private def defaultWrite(params: (TestAssociationHandle, ByteString)): Future[Boolean] = {
    registry.getRemoteReadHandlerFor(params._1) match {
      case Some(actor) ⇒ actor ! InboundPayload(params._2); Promise.successful(true).future
      case None ⇒
        Promise.failed(new IllegalStateException("No association present")).future
    }
  }

  private def defaultDisassociate(handle: TestAssociationHandle): Future[Unit] = {
    registry.deregisterAssociation(handle.key).foreach {
      case (handler1, handler2) ⇒
        val handler = if (handle.inbound) handler2 else handler1
        handler ! Disassociated
    }
    Promise.successful(()).future
  }

  /**
   * The [[akka.remote.transport.TestTransport.SwitchableLoggedBehavior]] for the write() method on handles. All
   * handle calls pass through this call.
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

  private[akka] def write(handle: TestAssociationHandle, payload: ByteString): Future[Boolean] =
    writeBehavior((handle, payload))

  private[akka] def disassociate(handle: TestAssociationHandle): Future[Unit] = disassociateBehavior(handle)

}

case class TestAssociationHandle(
  localAddress: Address,
  remoteAddress: Address,
  transport: TestTransport,
  inbound: Boolean) extends AssociationHandle {

  override val readHandlerPromise: Promise[ActorRef] = Promise()

  override def write(payload: ByteString): Future[Boolean] = transport.write(this, payload)

  override def disassociate(): Future[Unit] = transport.disassociate(this)

  /**
   * Key used in [[akka.remote.transport.TestTransport.AssociationRegistry]] to identify associations. Contains an
   * ordered pair of addresses, where the first element of the pair is always the initiator of the association.
   */
  val key = if (!inbound) (localAddress, remoteAddress) else (remoteAddress, localAddress)
}
