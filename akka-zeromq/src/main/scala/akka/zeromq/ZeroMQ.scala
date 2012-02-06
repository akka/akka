/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq;

/**
 * Java API for akka.zeromq
 */
object ZeroMQ {

  /**
   * The message that is sent when an ZeroMQ socket connects.
   * <p/>
   * <pre>
   * if (message == connecting()) {
   *   // Socket connected
   * }
   * </pre>
   *
   * @return the single instance of Connecting
   */
  def connecting() = Connecting

  /**
   * The message that is sent when an ZeroMQ socket disconnects.
   * <p/>
   * <pre>
   * if (message == closed()) {
   *   // Socket disconnected
   * }
   * </pre>
   *
   * @return the single instance of Closed
   */
  def closed() = Closed

  /**
   * The message to ask a ZeroMQ socket for its affinity configuration.
   * <p/>
   * <pre>
   * socket.ask(affinity())
   * </pre>
   *
   * @return the single instance of Affinity
   */
  def affinity() = Affinity

  /**
   * The message to ask a ZeroMQ socket for its backlog configuration.
   * <p/>
   * <pre>
   * socket.ask(backlog())
   * </pre>
   *
   * @return the single instance of Backlog
   */
  def backlog() = Backlog

  /**
   * The message to ask a ZeroMQ socket for its file descriptor configuration.
   * <p/>
   * <pre>
   * socket.ask(fileDescriptor())
   * </pre>
   *
   * @return the single instance of FileDescriptor
   */
  def fileDescriptor() = FileDescriptor

  /**
   * The message to ask a ZeroMQ socket for its identity configuration.
   * <p/>
   * <pre>
   * socket.ask(identity())
   * </pre>
   *
   * @return the single instance of Identity
   */
  def identity() = Identity

  /**
   * The message to ask a ZeroMQ socket for its linger configuration.
   * <p/>
   * <pre>
   * socket.ask(linger())
   * </pre>
   *
   * @return the single instance of Linger
   */
  def linger() = Linger

  /**
   * The message to ask a ZeroMQ socket for its max message size configuration.
   * <p/>
   * <pre>
   * socket.ask(maxMessageSize())
   * </pre>
   *
   * @return the single instance of MaxMsgSize
   */
  def maxMessageSize() = MaxMsgSize

  /**
   * The message to ask a ZeroMQ socket for its multicast hops configuration.
   * <p/>
   * <pre>
   * socket.ask(multicastHops())
   * </pre>
   *
   * @return the single instance of MulticastHops
   */
  def multicastHops() = MulticastHops

  /**
   * The message to ask a ZeroMQ socket for its multicast loop configuration.
   * <p/>
   * <pre>
   * socket.ask(multicastLoop())
   * </pre>
   *
   * @return the single instance of MulticastLoop
   */
  def multicastLoop() = MulticastLoop

  /**
   * The message to ask a ZeroMQ socket for its rate configuration.
   * <p/>
   * <pre>
   * socket.ask(rate())
   * </pre>
   *
   * @return the single instance of Rate
   */
  def rate() = Rate

  /**
   * The message to ask a ZeroMQ socket for its receive bufferSize configuration.
   * <p/>
   * <pre>
   * socket.ask(receiveBufferSize())
   * </pre>
   *
   * @return the single instance of ReceiveBufferSize
   */
  def receiveBufferSize() = ReceiveBufferSize

  /**
   * The message to ask a ZeroMQ socket for its receive high watermark configuration.
   * <p/>
   * <pre>
   * socket.ask(receiveHighWatermark())
   * </pre>
   *
   * @return the single instance of ReceiveHighWatermark
   */
  def receiveHighWatermark() = ReceiveHighWatermark

  /**
   * The message to ask a ZeroMQ socket for its reconnect interval configuration.
   * <p/>
   * <pre>
   * socket.ask(reconnectIVL())
   * </pre>
   *
   * @return the single instance of ReconnectIVL
   */
  def reconnectIVL() = ReconnectIVL

  /**
   * The message to ask a ZeroMQ socket for its max reconnect interval configuration.
   * <p/>
   * <pre>
   * socket.ask(reconnectIVLMax())
   * </pre>
   *
   * @return the single instance of ReconnectIVLMax
   */
  def reconnectIVLMax() = ReconnectIVLMax

  /**
   * The message to ask a ZeroMQ socket for its recovery interval configuration.
   * <p/>
   * <pre>
   * socket.ask(recoveryInterval())
   * </pre>
   *
   * @return the single instance of RecoveryInterval
   */
  def recoveryInterval() = RecoveryInterval

  /**
   * The message to ask a ZeroMQ socket for its send buffer size configuration.
   * <p/>
   * <pre>
   * socket.ask(sendBufferSize())
   * </pre>
   *
   * @return the single instance of SendBufferSize
   */
  def sendBufferSize() = SendBufferSize

  /**
   * The message to ask a ZeroMQ socket for its send high watermark configuration.
   * <p/>
   * <pre>
   * socket.ask(sendHighWatermark())
   * </pre>
   *
   * @return the single instance of SendHighWatermark
   */
  def sendHighWatermark() = SendHighWatermark

  /**
   * The message to ask a ZeroMQ socket for its swap configuration.
   * <p/>
   * <pre>
   * socket.ask(swap())
   * </pre>
   *
   * @return the single instance of Swap
   */
  def swap() = Swap
}