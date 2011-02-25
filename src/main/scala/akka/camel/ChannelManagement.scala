package akka.camel

import akka.actor._

/**
 * Reply channel management.
 *
 * @author Martin Krasser
 */
trait ChannelManagement { this: Actor => 
  /**
   * A reply channel that can be set via <code>storeChannel</code>.
   */
  protected var replyChannel: Option[Channel[Any]] = None

  /**
   * Manages a <code>replyChannel</code> for the <code>receive</code> partial function.
   * Sets a reply channel before calling <code>receive</code> and un-sets the reply channel
   * after <code>receive</code> terminated normally. The reply channel is not un-set if
   * <code>receive</code> throws an exception.
   */
  protected def manageReplyChannelFor(receive: PartialFunction[Any, Unit]): PartialFunction[Any, Unit] = {
    case msg => {
      replyChannel = try { Some(self.channel) } catch { case e => None }
      receive(msg)
      replyChannel = None
    }
  }
}