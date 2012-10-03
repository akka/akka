package akka.remote.transport

import TestTransport._
import akka.actor._
import akka.remote.transport.AssociationHandle._
import akka.remote.transport.Transport._
import akka.util.ByteString
import com.typesafe.config.Config
import java.util.concurrent.{ CopyOnWriteArrayList, ConcurrentHashMap }
import scala.concurrent.util.duration._
import scala.concurrent.{ Await, Future, Promise }

// Default EC is used, but this is just a test utility -- please forgive...
import scala.concurrent.ExecutionContext.Implicits.global

object TestTransport {

  type Behavior[A, B] = (A) ⇒ Future[B]

  /**
   * Test utility to make behavior of functions that return some Future[B] controllable from tests. This tool is able
   * to overwrite default behavior with any generic behavior, including failure, and exposes control to the timing of
   * the completition of the returned future.
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
   * @tparam A
   *   Parameter type of the wrapped function. If it takes multiple parameters it must be wrapped in a tuple.
   *
   * @tparam B
   *   Type parameter of the future that the original function returns.
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
      (x) ⇒ Promise.successful(c).future
    }

    /**
     * Changes the current behavior to return a failed future containing the given Throwable.
     *
     * @param e
     *   The throwable the failed future will contain.
     */
    def pushError(e: Throwable): Unit = push {
      (x) ⇒ Promise.failed(e).future
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
        (params: A) ⇒ for (delayed ← controlPromise.future; original ← originalBehavior(params)) yield original
      )

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
    private val handlersTable = new ConcurrentHashMap[(Address, Address), Future[(ActorRef, ActorRef)]]()

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
     * Records a mapping between an address and the corresponding (transport, actor) pair.
     *
     * @param transport
     *   The transport that is to be registered. The address of this transport will be used as key.
     * @param responsibleActor
     *   The actor that will handle the events for the given transport.
     */
    def registerTransport(transport: TestTransport, responsibleActor: ActorRef): Unit = {
      transportTable.put(transport.localAddress, (transport, responsibleActor))
    }

    /**
     * Indicates if all given transports were successfully registered. No associations can be established between
     * transports that are not yet registered.
     *
     * @param transports
     *   The transports that participate in the test case.
     * @return
     *   True if all transports are successfully registered.
     */
    def transportsReady(transports: TestTransport*): Boolean = {
      transports forall {
        t ⇒ transportTable.containsKey(t.localAddress)
      }
    }

    /**
     * Registers a Future of two actors corresponding to the two endpoints of an association.
     *
     * @param key
     *   Ordered pair of addresses representing an association. First element must be the address of the initiator.
     * @param readHandlers
     *   The future containing the actors that will be responsible for handling the events of the two endpoints of the
     *   association. Elements in the pair must be in the same order as the addresses in the key parameter.
     */
    def registerHandlePair(key: (Address, Address), readHandlers: Future[(ActorRef, ActorRef)]): Unit = {
      handlersTable.put(key, readHandlers)
    }

    /**
     * Removes an association.
     * @param key
     *   Ordered pair of addresses representing an association. First element is the address of the initiator.
     * @return
     *   The original entries.
     */
    def deregisterAssociation(key: (Address, Address)): Option[Future[(ActorRef, ActorRef)]] =
      Option(handlersTable.remove(key))

    /**
     * Tests if an association was registered.
     *
     * @param initiatorAddress The initiator of the association.
     * @param remoteAddress The other address of the association.
     *
     * @return True if there is an association for the given addresses.
     */
    def existsAssociation(initiatorAddress: Address, remoteAddress: Address): Boolean = {
      handlersTable.containsKey((initiatorAddress, remoteAddress))
    }

    /**
     * Returns the event handler actor corresponding to the remote endpoint of the given local handle. In other words
     * it returns the actor that will receive InboundPayload events when {{{write()}}} is called on the given handle.
     *
     * @param localHandle The handle
     * @return The option that contains the Future for the handler actor if exists.
     */
    def getRemoteReadHandlerFor(localHandle: TestAssociationHandle): Option[Future[ActorRef]] = {
      Option(handlersTable.get(localHandle.key)) map {
        case pairFuture: Future[(ActorRef, ActorRef)] ⇒ if (localHandle.inbound) {
          pairFuture.map { _._1 }
        } else {
          pairFuture.map { _._2 }
        }
      }
    }

    /**
     * Returns the Transport bound to the given address.
     *
     * @param address The address bound to the transport.
     * @return The transport if exists.
     */
    def transportFor(address: Address): Option[(TestTransport, ActorRef)] = Option(transportTable.get(address))

    /**
     * Resets the state of the registry. ''Warning!'' This method is not atomic.
     */
    def reset(): Unit = {
      clearLog()
      transportTable.clear()
      handlersTable.clear()
    }
  }

}

/*
  NOTE: This is a global shared state between different actor systems. The purpose of this class is to allow dynamically
  loaded TestTransports to set up a shared AssociationRegistry. Extensions could not be used for this, as the injection
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

/**
 * Transport implementation to be used for testing.
 *
 * The TestTransport is basically a shared memory between actor systems. The TestTransport could be programmed to
 * emulate different failure modes of a Transport implementation. TestTransport keeps a log of the activities it was
 * requested to do. This class is not optimized for performace and MUST not be used as an in-memory transport in
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

        val bothSides: Future[(ActorRef, ActorRef)] = for (
          actor1 ← localHandle.readHandlerPromise.future;
          actor2 ← remoteHandle.readHandlerPromise.future
        ) yield (actor1, actor2)

        registry.registerHandlePair(localHandle.key, bothSides)
        actor ! InboundAssociation(remoteHandle)

        Promise.successful(Ready(localHandle)).future

      case None ⇒
        Promise.successful(Fail(new IllegalArgumentException(s"No registered transport: $remoteAddress"))).future
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
  override def shutdown(): Unit = shutdownBehavior()

  private def defaultWrite(params: (TestAssociationHandle, ByteString)): Future[Boolean] = {
    registry.getRemoteReadHandlerFor(params._1) match {
      case Some(futureActor) ⇒
        val writePromise = Promise[Boolean]()
        futureActor.onSuccess {
          case actor => actor ! InboundPayload(params._2); writePromise.success(true)
        }
        writePromise.future
      case None ⇒
        Promise.failed(new IllegalStateException("No association present")).future
    }
  }

  private def defaultDisassociate(handle: TestAssociationHandle): Future[Unit] = {
    registry.deregisterAssociation(handle.key).foreach {
      case f: Future[(ActorRef, ActorRef)]⇒ f.onSuccess {
        case (handler1, handler2) =>
          val handler = if (handle.inbound) handler2 else handler1
          handler ! Disassociated
      }

    }
    Promise.successful(()).future
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
    Await.result(writeBehavior((handle, payload)), 3 seconds)

  private[akka] def disassociate(handle: TestAssociationHandle): Unit = disassociateBehavior(handle)

  override def toString: String = s"TestTransport($localAddress)"

}

case class TestAssociationHandle(
  localAddress: Address,
  remoteAddress: Address,
  transport: TestTransport,
  inbound: Boolean) extends AssociationHandle {

  override val readHandlerPromise: Promise[ActorRef] = Promise()

  override def write(payload: ByteString): Boolean = transport.write(this, payload)

  override def disassociate(): Unit = transport.disassociate(this)

  /**
   * Key used in [[akka.remote.transport.TestTransport.AssociationRegistry]] to identify associations. Contains an
   * ordered pair of addresses, where the first element of the pair is always the initiator of the association.
   */
  val key = if (!inbound) (localAddress, remoteAddress) else (remoteAddress, localAddress)
}
