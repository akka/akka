/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.transport

import FailureInjectorTransportAdapter._
import akka.AkkaException
import akka.actor.{ Address, ExtendedActorSystem }
import akka.event.{ Logging, LoggingAdapter }
import akka.remote.transport.AssociationHandle.{ HandleEvent, HandleEventListener }
import akka.remote.transport.Transport._
import akka.util.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

import com.github.ghik.silencer.silent

import scala.concurrent.{ Future, Promise }
import scala.util.control.NoStackTrace

@SerialVersionUID(1L)
final case class FailureInjectorException(msg: String) extends AkkaException(msg) with NoStackTrace

class FailureInjectorProvider extends TransportAdapterProvider {

  override def create(wrappedTransport: Transport, system: ExtendedActorSystem): Transport =
    new FailureInjectorTransportAdapter(wrappedTransport, system)

}

/**
 * INTERNAL API
 */
private[remote] object FailureInjectorTransportAdapter {
  val FailureInjectorSchemeIdentifier = "gremlin"

  trait FailureInjectorCommand
  @SerialVersionUID(1L)
  @deprecated("Not implemented", "2.5.22")
  final case class All(mode: GremlinMode)
  @SerialVersionUID(1L)
  final case class One(remoteAddress: Address, mode: GremlinMode)

  sealed trait GremlinMode
  @SerialVersionUID(1L)
  case object PassThru extends GremlinMode {

    /**
     * Java API: get the singleton instance
     */
    def getInstance = this
  }
  @SerialVersionUID(1L)
  final case class Drop(outboundDropP: Double, inboundDropP: Double) extends GremlinMode
}

/**
 * INTERNAL API
 */
private[remote] class FailureInjectorTransportAdapter(
    wrappedTransport: Transport,
    val extendedSystem: ExtendedActorSystem)
    extends AbstractTransportAdapter(wrappedTransport)(extendedSystem.dispatchers.internalDispatcher)
    with AssociationEventListener {

  private def rng = ThreadLocalRandom.current()
  private val log = Logging(extendedSystem, getClass.getName)
  private val shouldDebugLog: Boolean = extendedSystem.settings.config.getBoolean("akka.remote.classic.gremlin.debug")

  @volatile private var upstreamListener: Option[AssociationEventListener] = None
  private[transport] val addressChaosTable = new ConcurrentHashMap[Address, GremlinMode]()

  override val addedSchemeIdentifier = FailureInjectorSchemeIdentifier
  protected def maximumOverhead = 0

  override def managementCommand(cmd: Any): Future[Boolean] = cmd match {
    case All(_) =>
      Future.failed(
        new IllegalArgumentException("Setting the mode for all addresses at once is not currently implemented"))
    case One(address, mode) =>
      //  don't care about the protocol part - we are injected in the stack anyway!
      addressChaosTable.put(address.copy(protocol = "", system = ""), mode)
      Future.successful(true)
    case _ => wrappedTransport.managementCommand(cmd)
  }

  protected def interceptListen(
      listenAddress: Address,
      listenerFuture: Future[AssociationEventListener]): Future[AssociationEventListener] = {
    log.warning("FailureInjectorTransport is active on this system. Gremlins might munch your packets.")
    listenerFuture.foreach {
      // Side effecting: As this class is not an actor, the only way to safely modify state is through volatile vars.
      // Listen is called only during the initialization of the stack, and upstreamListener is not read before this
      // finishes.
      listener =>
        upstreamListener = Some(listener)
    }
    Future.successful(this)
  }

  protected def interceptAssociate(remoteAddress: Address, statusPromise: Promise[AssociationHandle]): Unit = {
    // Association is simulated to be failed if there was either an inbound or outbound message drop
    if (shouldDropInbound(remoteAddress, (), "interceptAssociate") || shouldDropOutbound(
          remoteAddress,
          (),
          "interceptAssociate"))
      statusPromise.failure(new FailureInjectorException("Simulated failure of association to " + remoteAddress))
    else
      statusPromise.completeWith(wrappedTransport.associate(remoteAddress).map { handle =>
        addressChaosTable.putIfAbsent(handle.remoteAddress.copy(protocol = "", system = ""), PassThru)
        new FailureInjectorHandle(handle, this)
      })
  }

  def notify(ev: AssociationEvent): Unit = ev match {
    case InboundAssociation(handle) if shouldDropInbound(handle.remoteAddress, ev, "notify") => //Ignore
    case _ =>
      upstreamListener match {
        case Some(listener) => listener.notify(interceptInboundAssociation(ev))
        case None           =>
      }
  }

  def interceptInboundAssociation(ev: AssociationEvent): AssociationEvent = ev match {
    case InboundAssociation(handle) => InboundAssociation(FailureInjectorHandle(handle, this))
    case _                          => ev
  }

  def shouldDropInbound(remoteAddress: Address, instance: Any, debugMessage: String): Boolean =
    chaosMode(remoteAddress) match {
      case PassThru => false
      case Drop(_, inboundDropP) =>
        if (rng.nextDouble() <= inboundDropP) {
          if (shouldDebugLog)
            log.debug("Dropping inbound [{}] for [{}] {}", instance.getClass, remoteAddress, debugMessage)
          true
        } else false
    }

  def shouldDropOutbound(remoteAddress: Address, instance: Any, debugMessage: String): Boolean =
    chaosMode(remoteAddress) match {
      case PassThru => false
      case Drop(outboundDropP, _) =>
        if (rng.nextDouble() <= outboundDropP) {
          if (shouldDebugLog)
            log.debug("Dropping outbound [{}] for [{}] {}", instance.getClass, remoteAddress, debugMessage)
          true
        } else false
    }

  def chaosMode(remoteAddress: Address): GremlinMode = {
    val mode = addressChaosTable.get(remoteAddress.copy(protocol = "", system = ""))
    if (mode eq null) PassThru else mode
  }
}

/**
 * INTERNAL API
 */
private[remote] final case class FailureInjectorHandle(
    _wrappedHandle: AssociationHandle,
    private val gremlinAdapter: FailureInjectorTransportAdapter)
    extends AbstractTransportAdapterHandle(_wrappedHandle, FailureInjectorSchemeIdentifier)
    with HandleEventListener {
  import gremlinAdapter.extendedSystem.dispatcher

  @volatile private var upstreamListener: HandleEventListener = null

  override val readHandlerPromise: Promise[HandleEventListener] = Promise()
  readHandlerPromise.future.foreach { listener =>
    upstreamListener = listener
    wrappedHandle.readHandlerPromise.success(this)
  }

  override def write(payload: ByteString): Boolean =
    if (!gremlinAdapter.shouldDropOutbound(wrappedHandle.remoteAddress, payload, "handler.write"))
      wrappedHandle.write(payload)
    else true

  override def disassociate(reason: String, log: LoggingAdapter): Unit =
    wrappedHandle.disassociate(reason, log)

  @deprecated(
    message = "Use method that states reasons to make sure disassociation reasons are logged.",
    since = "2.5.3")
  @silent
  override def disassociate(): Unit =
    wrappedHandle.disassociate()

  override def notify(ev: HandleEvent): Unit =
    if (!gremlinAdapter.shouldDropInbound(wrappedHandle.remoteAddress, ev, "handler.notify"))
      upstreamListener.notify(ev)

}
