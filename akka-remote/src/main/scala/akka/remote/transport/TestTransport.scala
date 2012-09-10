package akka.remote.transport

import akka.actor.{ ActorRef, Address }
import scala.concurrent.{ Promise, Future }
import akka.remote.transport.Transport._
import akka.util.ByteString
// Default is used, but this is a test util
import scala.concurrent.ExecutionContext.Implicits.global

object TestTransport {
  type Behavior[A, B] = (A) ⇒ Future[B]

  class SwitchableLoggedBehavior[A, B](defaultBehavior: Behavior[A, B], logCallback: (A) ⇒ Unit) extends Behavior[A, B] {
    // TODO: add synchronize
    private val stackLock = new AnyRef
    private var behaviorStack = List(defaultBehavior)

    def push(behavior: Behavior[A, B]): Unit = stackLock synchronized {
      behaviorStack ::= behavior
    }

    def currentBehavior = stackLock synchronized behaviorStack.head

    def pushConstant(c: B): Unit = push { (x) ⇒ Promise.successful(c).future }
    def pushError(e: Throwable): Unit = push { (x) ⇒ Promise.failed(e).future }

    def pushDelayed: Promise[Unit] = {
      val controlPromise: Promise[Unit] = Promise()

      val originalBehavior = currentBehavior
      val newBehavior: Behavior[A, B] = (params: A) ⇒
        for (delayed ← controlPromise.future; original ← originalBehavior(params)) yield original

      push(newBehavior)
      controlPromise
    }

    def pop(): Unit = stackLock synchronized {
      if (behaviorStack.size > 1) {
        behaviorStack = behaviorStack.tail
      }
    }

    def apply(params: A): Future[B] = {
      logCallback(params)
      currentBehavior(params)
    }
  }

  sealed trait Activity
  case object Listen extends Activity
  case class Associate(remoteAddress: Address) extends Activity
  case object Shutdown extends Activity

  case class Write(sender: Address, recipient: Address, payload: ByteString) extends Activity
  case class Disassociate(requester: Address, remote: Address) extends Activity

  class AssociationRegistry {
    private val logLock = new AnyRef()
    // protected by logLock
    private var activityLog: List[Activity] = List()

    def logActivity(activity: Activity): Unit = logLock synchronized {
      activityLog ::= activity
    }

    def logSnapshot: Seq[Activity] = logLock synchronized {
      activityLog.reverse
    }

    def clearLog = logLock synchronized {
      activityLog = List()
    }
  }
}

/**
 * Transport implementation to be used for testing.
 *
 * The TestTransport is basically a shared memory between actor systems. The TestTransport could be programmed to
 * emulate different failure modes of a Transport implementation. It also keeps a log of the transport activities.
 */
class TestTransport extends Transport {
  import akka.remote.transport.TestTransport._

  // This relies on starting both ActorSystems under test in the same thread. This is usually true for individual
  // test cases
  private val registry = new ThreadLocal[AssociationRegistry] {
    override def initialValue() = new AssociationRegistry
  }

  private def defaultListen: Future[(Address, Promise[ActorRef])] = ???
  private def defaultAssociate(remoteAddress: Address): Future[Status] = ???
  private def defaultShutdown: Future[Unit] = ???

  val listenOperation = new SwitchableLoggedBehavior[Unit, (Address, Promise[ActorRef])](
    (unit) ⇒ defaultListen,
    (unit) ⇒ registry.get.logActivity(Listen))
  val associateOperation = new SwitchableLoggedBehavior[Address, Status](
    defaultAssociate _,
    (address) ⇒ registry.get.logActivity(Associate(address)))
  val shutdownOperation = new SwitchableLoggedBehavior[Unit, Unit](
    (unit) ⇒ defaultShutdown,
    (unit) ⇒ registry.get.logActivity(Shutdown))

  override def listen: Future[(Address, Promise[ActorRef])] = listenOperation()
  override def associate(remoteAddress: Address): Future[Status] = associateOperation(remoteAddress)
  override def shutdown(): Future[Unit] = shutdownOperation()

  private def defaultWrite(params: (TestAssociationHandle, ByteString)): Future[Boolean] = ???
  private def defaultDisassociate(handle: TestAssociationHandle): Future[Unit] = ???

  val writeOperation = new SwitchableLoggedBehavior[(TestAssociationHandle, ByteString), Boolean](
    defaultBehavior = { defaultWrite _ },
    logCallback = {
      case (handle, payload) ⇒ registry.get.logActivity(Write(handle.localEndpointAddress, handle.remoteEndpointAddress, payload))
    })
  val disassociateOperation = new SwitchableLoggedBehavior[TestAssociationHandle, Unit](
    defaultBehavior = { defaultDisassociate _ },
    logCallback = { (handle) ⇒ registry.get.logActivity(Disassociate(handle.localEndpointAddress, handle.remoteEndpointAddress)) })

  private[akka] def write(handle: TestAssociationHandle, payload: ByteString): Future[Boolean] = writeOperation((handle, payload))
  private[akka] def disassociate(handle: TestAssociationHandle): Future[Unit] = disassociateOperation(handle)

}

case class TestAssociationHandle(
  val localEndpointAddress: Address,
  val remoteEndpointAddress: Address,
  val transport: TestTransport) extends AssociationHandle {

  override def readHandlerPromise: Promise[ActorRef] = Promise()

  override def write(payload: ByteString): Future[Boolean] = transport.write(this, payload)

  override def disassociate(): Future[Unit] = transport.disassociate(this)
}
