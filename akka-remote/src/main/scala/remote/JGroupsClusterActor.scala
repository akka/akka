package se.scalablesolutions.akka.remote

import org.jgroups.{JChannel, View => JG_VIEW, Address, Message => JG_MSG, ExtendedMembershipListener, Receiver}
import org.jgroups.util.Util

/**
 * Clustering support via JGroups.
 *
 * @author Viktor Klang
 */
class JGroupsClusterActor extends BasicClusterActor {
  import scala.collection.JavaConversions._
  import se.scalablesolutions.akka.remote.ClusterActor._

  type ADDR_T = Address

  @volatile private var isActive = false
  @volatile private var channel: Option[JChannel] = None

  protected def boot = {
    log info "Booting JGroups-based cluster"
    isActive = true

    // Set up the JGroups local endpoint
    channel = Some(new JChannel {
      setReceiver(new Receiver with ExtendedMembershipListener {
        def getState: Array[Byte] = null

        def setState(state: Array[Byte]): Unit = ()

        def receive(m: JG_MSG): Unit =
          if (isActive && m.getSrc != channel.map(_.getAddress).getOrElse(m.getSrc)) self ! Message(m.getSrc,m.getRawBuffer)

        def viewAccepted(view: JG_VIEW): Unit =
          if (isActive) self ! View(Set[ADDR_T]() ++ view.getMembers - channel.get.getAddress)

        def suspect(a: Address): Unit =
          if (isActive) self ! Zombie(a)

        def block(): Unit =
          log debug "UNSUPPORTED: JGroupsClusterActor::block" //TODO HotSwap to a buffering body

        def unblock(): Unit =
          log debug "UNSUPPORTED: JGroupsClusterActor::unblock" //TODO HotSwap back and flush the buffer
      })
    })

    channel.foreach(_.connect(name))
  }

  protected def toOneNode(dest : Address, msg: Array[Byte]): Unit =
    for (c <- channel) c.send(new JG_MSG(dest, null, msg))

  protected def toAllNodes(msg : Array[Byte]): Unit =
    for (c <- channel) c.send(new JG_MSG(null, null, msg))

  override def shutdown = {
    super.shutdown
    log info ("Shutting down %s", toString)
    isActive = false
    channel.foreach(Util shutdown _)
    channel = None
  }
}
