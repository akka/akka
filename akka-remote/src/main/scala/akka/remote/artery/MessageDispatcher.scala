/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.ActorSelectionMessage
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.InternalActorRef
import akka.actor.LocalRef
import akka.actor.PossiblyHarmful
import akka.actor.RepointableRef
import akka.dispatch.sysmsg.SystemMessage
import akka.event.Logging
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteRef

/**
 * INTERNAL API
 */
private[akka] class MessageDispatcher(
  system: ExtendedActorSystem,
  provider: RemoteActorRefProvider) {

  private val remoteDaemon = provider.remoteDaemon
  private val log = Logging(system.eventStream, getClass.getName)

  def dispatch(recipient: InternalActorRef,
               recipientAddress: Address,
               message: AnyRef,
               senderOption: Option[ActorRef]): Unit = {

    import provider.remoteSettings._

    val sender: ActorRef = senderOption.getOrElse(system.deadLetters)
    val originalReceiver = recipient.path

    def msgLog = s"RemoteMessage: [$message] to [$recipient]<+[$originalReceiver] from [$sender()]"

    recipient match {

      case `remoteDaemon` ⇒
        if (UntrustedMode) log.debug("dropping daemon message in untrusted mode")
        else {
          if (LogReceive) log.debug("received daemon message {}", msgLog)
          remoteDaemon ! message
        }

      case l @ (_: LocalRef | _: RepointableRef) if l.isLocal ⇒
        if (LogReceive) log.debug("received local message {}", msgLog)
        message match {
          case sel: ActorSelectionMessage ⇒
            if (UntrustedMode && (!TrustedSelectionPaths.contains(sel.elements.mkString("/", "/", "")) ||
              sel.msg.isInstanceOf[PossiblyHarmful] || l != provider.rootGuardian))
              log.debug("operating in UntrustedMode, dropping inbound actor selection to [{}], " +
                "allow it by adding the path to 'akka.remote.trusted-selection-paths' configuration",
                sel.elements.mkString("/", "/", ""))
            else
              // run the receive logic for ActorSelectionMessage here to make sure it is not stuck on busy user actor
              ActorSelection.deliverSelection(l, sender, sel)
          case msg: PossiblyHarmful if UntrustedMode ⇒
            log.debug("operating in UntrustedMode, dropping inbound PossiblyHarmful message of type [{}]", msg.getClass.getName)
          case msg: SystemMessage ⇒ l.sendSystemMessage(msg)
          case msg                ⇒ l.!(msg)(sender)
        }

      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal && !UntrustedMode ⇒
        if (LogReceive) log.debug("received remote-destined message {}", msgLog)
        if (provider.transport.addresses(recipientAddress))
          // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
          r.!(message)(sender)
        else
          log.error("dropping message [{}] for non-local recipient [{}] arriving at [{}] inbound addresses are [{}]",
            message.getClass, r, recipientAddress, provider.transport.addresses.mkString(", "))

      case r ⇒ log.error("dropping message [{}] for unknown recipient [{}] arriving at [{}] inbound addresses are [{}]",
        message.getClass, r, recipientAddress, provider.transport.addresses.mkString(", "))

    }
  }

}
