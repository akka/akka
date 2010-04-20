package se.scalablesolutions.akka.actor

import _root_.java.net.InetSocketAddress
import _root_.se.scalablesolutions.akka.config.ScalaConfig.RestartCallbacks
import _root_.se.scalablesolutions.akka.dispatch.MessageDispatcher


final class ActiveObjectConfiguration {
  private[akka] var _timeout: Long = Actor.TIMEOUT
  private[akka] var _restartCallbacks: Option[RestartCallbacks] = None
  private[akka] var _transactionRequired = false
  private[akka] var _host: Option[InetSocketAddress] = None
  private[akka] var _messageDispatcher: Option[MessageDispatcher] = None

  def timeout(timeout: Long) : ActiveObjectConfiguration = {
    _timeout = timeout
    this
  }

  def restartCallbacks(pre: String, post: String) : ActiveObjectConfiguration = {
    _restartCallbacks = Some(new RestartCallbacks(pre, post))
    this
  }

  def makeTransactionRequired() : ActiveObjectConfiguration = {
    _transactionRequired = true;
    this
  }

  def makeRemote(hostname: String, port: Int) : ActiveObjectConfiguration = {
    _host = Some(new InetSocketAddress(hostname, port))
    this
  }

  def dispatcher(messageDispatcher: MessageDispatcher) : ActiveObjectConfiguration = {
    _messageDispatcher = Some(messageDispatcher)
    this
  }
}