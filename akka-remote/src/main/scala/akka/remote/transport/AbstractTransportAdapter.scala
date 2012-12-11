package akka.remote.transport

import scala.language.postfixOps
import akka.actor._
import akka.pattern.ask
import akka.remote.transport.Transport._
import akka.remote.{ RARP, RemotingSettings, RemoteActorRefProvider }
import scala.collection.immutable
import scala.concurrent.{ Await, ExecutionContext, Promise, Future }
import scala.util.Success
import scala.util.Failure
import akka.remote.Remoting.RegisterTransportActor
import akka.util.Timeout
import scala.concurrent.duration._

trait TransportAdapterProvider extends ((Transport, ExtendedActorSystem) ⇒ Transport)

class TransportAdapters(system: ExtendedActorSystem) extends Extension {
  val settings = new RemotingSettings(RARP(system).provider.remoteSettings.config)

  private val adaptersTable: Map[String, TransportAdapterProvider] = for ((name, fqn) ← settings.Adapters) yield {
    name -> system.dynamicAccess.createInstanceFor[TransportAdapterProvider](fqn, immutable.Seq.empty).recover({
      case exception ⇒ throw new IllegalArgumentException("Cannot instantiate transport adapter" + fqn, exception)
    }).get
  }

  def getAdapterProvider(name: String): TransportAdapterProvider = adaptersTable.get(name) match {
    case Some(provider) ⇒ provider
    case None           ⇒ throw new IllegalArgumentException("There is no registered transport adapter provider with name: " + name)
  }
}

object TransportAdaptersExtension extends ExtensionId[TransportAdapters] with ExtensionIdProvider {
  override def get(system: ActorSystem): TransportAdapters = super.get(system)
  override def lookup = TransportAdaptersExtension
  override def createExtension(system: ExtendedActorSystem): TransportAdapters =
    new TransportAdapters(system)
}

trait SchemeAugmenter {
  protected def addedSchemeIdentifier: String

  protected def augmentScheme(originalScheme: String): String = s"$originalScheme.$addedSchemeIdentifier"

  protected def augmentScheme(address: Address): Address = address.copy(protocol = augmentScheme(address.protocol))

  protected def removeScheme(scheme: String): String = if (scheme.endsWith(s".$addedSchemeIdentifier"))
    scheme.take(scheme.length - addedSchemeIdentifier.length - 1)
  else scheme

  protected def removeScheme(address: Address): Address = address.copy(protocol = removeScheme(address.protocol))
}

/**
 * An adapter that wraps a transport and provides interception
 */
abstract class AbstractTransportAdapter(protected val wrappedTransport: Transport, implicit val ec: ExecutionContext)
  extends Transport with SchemeAugmenter {

  protected def maximumOverhead: Int

  protected def interceptListen(listenAddress: Address,
                                listenerFuture: Future[AssociationEventListener]): AssociationEventListener

  protected def interceptAssociate(remoteAddress: Address, statusPromise: Promise[Status]): Unit

  override def schemeIdentifier: String = augmentScheme(wrappedTransport.schemeIdentifier)

  override def isResponsibleFor(address: Address): Boolean = wrappedTransport.isResponsibleFor(address)

  override def maximumPayloadBytes: Int = wrappedTransport.maximumPayloadBytes - maximumOverhead

  override def listen: Future[(Address, Promise[AssociationEventListener])] = {
    val listenPromise: Promise[(Address, Promise[AssociationEventListener])] = Promise()
    val upstreamListenerPromise: Promise[AssociationEventListener] = Promise()
    wrappedTransport.listen.onComplete {
      case Success((listenAddress, listenerPromise)) ⇒
        // Register to downstream
        listenerPromise.success(interceptListen(listenAddress, upstreamListenerPromise.future))
        // Notify upstream
        listenPromise.success((augmentScheme(listenAddress), upstreamListenerPromise))
      case Failure(reason) ⇒ listenPromise.failure(reason)
    }
    listenPromise.future
  }

  override def associate(remoteAddress: Address): Future[Status] = {
    // Prepare a future, and pass its promise to the manager
    val statusPromise: Promise[Status] = Promise()

    interceptAssociate(removeScheme(remoteAddress), statusPromise)

    statusPromise.future
  }

  override def shutdown(): Unit = wrappedTransport.shutdown()

}

abstract class AbstractTransportAdapterHandle(val originalLocalAddress: Address,
                                              val originalRemoteAddress: Address,
                                              val wrappedHandle: AssociationHandle,
                                              val addedSchemeIdentifier: String) extends AssociationHandle
  with SchemeAugmenter {

  def this(wrappedHandle: AssociationHandle, addedSchemeIdentifier: String) =
    this(wrappedHandle.localAddress,
      wrappedHandle.remoteAddress,
      wrappedHandle,
      addedSchemeIdentifier)

  override val localAddress = augmentScheme(originalLocalAddress)
  override val remoteAddress = augmentScheme(originalRemoteAddress)

}

object ActorTransportAdapter {
  sealed trait TransportOperation

  case class ListenerRegistered(listener: AssociationEventListener) extends TransportOperation
  case class AssociateUnderlying(remoteAddress: Address, statusPromise: Promise[Status]) extends TransportOperation
  case class ListenUnderlying(listenAddress: Address,
                              upstreamListener: Future[AssociationEventListener]) extends TransportOperation
  case object DisassociateUnderlying extends TransportOperation
}

abstract class ActorTransportAdapter(wrappedTransport: Transport, system: ActorSystem)
  extends AbstractTransportAdapter(wrappedTransport, system.dispatcher) {
  import ActorTransportAdapter._
  private implicit val timeout = new Timeout(3 seconds)

  protected def managerName: String
  protected def managerProps: Props
  // The blocking call below is only called during the startup sequence.
  protected val manager = Await.result(registerManager(), 3 seconds)

  private def registerManager(): Future[ActorRef] =
    (system.actorFor("/system/transports") ? RegisterTransportActor(managerProps, managerName)).mapTo[ActorRef]

  protected def interceptListen(listenAddress: Address,
                                listenerPromise: Future[AssociationEventListener]): AssociationEventListener = {
    manager ! ListenUnderlying(listenAddress, listenerPromise)
    manager
  }

  override def interceptAssociate(remoteAddress: Address, statusPromise: Promise[Status]): Unit =
    manager ! AssociateUnderlying(remoteAddress, statusPromise)

  override def shutdown(): Unit = manager ! PoisonPill
}

