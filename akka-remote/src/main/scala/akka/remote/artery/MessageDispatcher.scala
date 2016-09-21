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
import akka.util.OptionVal
import akka.event.LoggingReceive

/**
 * INTERNAL API
 */
private[akka] class MessageDispatcher(
  system:   ExtendedActorSystem,
  provider: RemoteActorRefProvider) {

  private val remoteDaemon = provider.remoteDaemon
  private val log = Logging(system, getClass.getName)
  private val debugLogEnabaled = log.isDebugEnabled

  def dispatch(
    recipient:        InternalActorRef,
    recipientAddress: Address,
    message:          AnyRef,
    senderOption:     OptionVal[ActorRef]): Unit = {

    import provider.remoteSettings.Artery._
    import Logging.messageClassName

    val sender: ActorRef = senderOption.getOrElse(system.deadLetters)
    val originalReceiver = recipient.path

    recipient match {

      case `remoteDaemon` ⇒
        if (UntrustedMode) {
          if (debugLogEnabaled) log.debug(
            "dropping daemon message [{}] in untrusted mode",
            messageClassName(message))
        } else {
          if (LogReceive && debugLogEnabaled) log.debug(
            "received daemon message [{}] from [{}]",
            messageClassName(message), senderOption.getOrElse(""))
          remoteDaemon ! message
        }

      case l @ (_: LocalRef | _: RepointableRef) if l.isLocal ⇒
        if (LogReceive && debugLogEnabaled) log.debug(
          "received message [{}] to [{}] from [{}]",
          messageClassName(message), recipient, senderOption.getOrElse(""))
        message match {
          case sel: ActorSelectionMessage ⇒
            if (UntrustedMode && (!TrustedSelectionPaths.contains(sel.elements.mkString("/", "/", "")) ||
              sel.msg.isInstanceOf[PossiblyHarmful] || l != provider.rootGuardian)) {
              if (debugLogEnabaled) log.debug(
                "operating in UntrustedMode, dropping inbound actor selection to [{}], " +
                  "allow it by adding the path to 'akka.remote.trusted-selection-paths' configuration",
                sel.elements.mkString("/", "/", ""))
            } else
              // run the receive logic for ActorSelectionMessage here to make sure it is not stuck on busy user actor
              ActorSelection.deliverSelection(l, sender, sel)
          case msg: PossiblyHarmful if UntrustedMode ⇒
            if (debugLogEnabaled) log.debug(
              "operating in UntrustedMode, dropping inbound PossiblyHarmful message of type [{}] to [{}] from [{}]",
              messageClassName(msg), recipient, senderOption.getOrElse(""))
          case msg: SystemMessage ⇒ l.sendSystemMessage(msg)
          case msg                ⇒ l.!(msg)(sender)
        }

      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal && !UntrustedMode ⇒
        if (LogReceive && debugLogEnabaled) log.debug(
          "received remote-destined message [{}] to [{}] from [{}]",
          messageClassName(message), recipient, senderOption.getOrElse(""))
        // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
        r.!(message)(sender)

      case r ⇒ log.error(
        "dropping message [{}] for unknown recipient [{}]", messageClassName(message), r, recipientAddress)

    }
  }

}
