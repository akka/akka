package akka.remote.actmote

import akka.remote._
import akka.actor._
import akka.dispatch.SystemMessage
import akka.event.LoggingAdapter

trait RemoteMessageDispatchHelper {

  def log: LoggingAdapter
  def provider: RemoteActorRefProvider
  def address: Address
  def useUntrustedMode: Boolean

  /**
   * Call this method with an inbound RemoteMessage and this will take care of security (see: "useUntrustedMode")
   * as well as making sure that the message ends up at its destination (best effort).
   * There is also a fair amount of logging produced by this method, which is good for debugging.
   */
  def receiveMessage(remoteMessage: RemoteMessage): Unit = {
    val remoteDaemon = provider.remoteDaemon

    remoteMessage.recipient match {
      case `remoteDaemon` ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received daemon message {}", remoteMessage)
        remoteMessage.payload match {
          case m @ (_: DaemonMsg | _: Terminated) ⇒
            try remoteDaemon ! m catch {
              case e: Exception ⇒ log.error(e, "exception while processing remote command {} from {}", m, remoteMessage.sender)
            }
          case x ⇒ log.warning("remoteDaemon received illegal message {} from {}", x, remoteMessage.sender)
        }
      case l @ (_: LocalRef | _: RepointableRef) if l.isLocal ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received local message {}", remoteMessage)
        remoteMessage.payload match {
          case msg: PossiblyHarmful if useUntrustedMode ⇒ log.warning("operating in UntrustedMode, dropping inbound PossiblyHarmful message of type {}", msg.getClass)
          case msg: SystemMessage                       ⇒ l.sendSystemMessage(msg)
          case msg                                      ⇒ l.!(msg)(remoteMessage.sender)
        }
      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received remote-destined message {}", remoteMessage)
        remoteMessage.originalReceiver match {
          case AddressFromURIString(address) if address == provider.transport.address ⇒
            // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
            r.!(remoteMessage.payload)(remoteMessage.sender)
          case r ⇒ log.error("dropping message {} for non-local recipient {} arriving at {} inbound address is {}", remoteMessage.payload, r, address, provider.transport.address)
        }
      case r ⇒ log.error("dropping message {} for unknown recipient {} arriving at {} inbound address is {}", remoteMessage.payload, r, address, provider.transport.address)
    }
  }
}