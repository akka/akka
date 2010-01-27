package se.scalablesolutions.akka.remote

import org.jgroups.{JChannel, View => JG_VIEW, Address, Message => JG_MSG, ExtendedMembershipListener, Receiver}

/**
 * Clustering support via JGroups.
 */
class JGroupsClusterActor extends ClusterActor {
  import ClusterActor._
  import org.scala_tools.javautils.Imports._

  type ADDR_T = Address

  @volatile private var isActive = false
  @volatile private var channel: Option[JChannel] = None

  override def init = {
    super.init
    log debug "Initiating JGroups-based cluster actor"
    val me = this
    isActive = true

    // Set up the JGroups local endpoint
    channel = Some(new JChannel {
      setReceiver(new Receiver with ExtendedMembershipListener {
        def getState: Array[Byte] = null

        def setState(state: Array[Byte]): Unit = ()

        def receive(m: JG_MSG): Unit =
          if (isActive && m.getSrc != channel.map(_.getAddress).getOrElse(m.getSrc)) me send Message(m.getSrc,m.getRawBuffer)

        def viewAccepted(view: JG_VIEW): Unit =
          if (isActive) me send View(Set[ADDR_T]() ++ view.getMembers.asScala - channel.get.getAddress)

        def suspect(a: Address): Unit =
          if (isActive) me send Zombie(a)

        def block: Unit =
          log debug "UNSUPPORTED: JGroupsClusterActor::block" //TODO HotSwap to a buffering body

        def unblock: Unit =
          log debug "UNSUPPORTED: JGroupsClusterActor::unblock" //TODO HotSwap back and flush the buffer
      })
    })
    channel.map(_.connect(name))
  }

  protected def toOneNode(dest : Address, msg: Array[Byte]): Unit =
    for (c <- channel) c.send(new JG_MSG(dest, null, msg))

  protected def toAllNodes(msg : Array[Byte]) : Unit =
    for (c <- channel) c.send(new JG_MSG(null, null, msg))

  override def shutdown = {
    super.shutdown
    log debug ("Shutting down %s", toString)
    isActive = false
    channel.foreach(_.shutdown)
    channel = None
  }
}