package se.scalablesolutions.akka.cluster

import se.scalablesolutions.akka.Config.config
import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.util.Logging
import org.jgroups.{JChannel,View,Address,Message,ExtendedMembershipListener,Receiver,SetStateEvent}
import se.scalablesolutions.akka.serialization.Serializer.Protobuf
import se.scalablesolutions.akka.config.ScalaConfig.RemoteAddress
import scala.collection.immutable.{Map,HashMap,HashSet}
import org.jgroups.util.Util
import se.scalablesolutions.akka.nio.RemoteServer
import se.scalablesolutions.akka.cluster.Cluster.Node

trait Cluster {
    def members : List[Node]
    def name : String
    def registerLocalNode(server : RemoteAddress)   : Unit
    def deregisterLocalNode(server : RemoteAddress) : Unit
}

abstract class ClusterActor(val name : String) extends Actor with Cluster

object Cluster extends Cluster {
      case class Node(endpoints : List[RemoteAddress])

      lazy val impl : Option[ClusterActor] = {
        config.getString("akka.remote.cluster.actor") map ( name => {
             Class.forName(name)
                       .getDeclaredConstructor(Array(classOf[String]): _*)
                       .newInstance(config.getString("akka.remote.cluster.name") getOrElse "default")
                       .asInstanceOf[ClusterActor]
        })
      }
      
      def name = impl.map(_.name).getOrElse("No cluster")
      def members = impl.map(_.members).getOrElse(Nil)
      def registerLocalNode(server : RemoteAddress)   : Unit = impl.map(_.registerLocalNode(server))
      def deregisterLocalNode(server : RemoteAddress) : Unit = impl.map(_.deregisterLocalNode(server))
}

object JGroupsClusterActor {
    //Message types
    case object PapersPlease
    case class Papers(addresses : List[RemoteAddress])
    case object Block
    case object Unblock
    case class Zombie(address : Address)
    case class RegisterLocalNode(server : RemoteAddress)
    case class DeregisterLocalNode(server : RemoteAddress)
}

class JGroupsClusterActor(name : String) extends ClusterActor(name)
{
    import JGroupsClusterActor._
    import org.scala_tools.javautils.Implicits._
    
    private var local   : Node              = Node(Nil)
    private var channel : JChannel          = null
    private var remotes : Map[Address,Node] = Map()
  
    override def init(config : AnyRef) = {
      remotes = new HashMap[Address,Node]
      val me  = this
      channel = new JChannel {
        setReceiver(new Receiver with ExtendedMembershipListener {
          def getState : Array[Byte]               = null
          def setState(state : Array[Byte]) : Unit = ()
          def receive(msg : Message)        : Unit = me ! msg
          def viewAccepted(view : View)     : Unit = me ! view
          def suspect(a : Address)          : Unit = me ! Zombie(a)
          def block                         : Unit = me ! Block
          def unblock                       : Unit = me ! Unblock
        })
      }
      channel connect name
    }

    protected def serializer = Protobuf

    private def broadcast[T <: AnyRef](recipients : Iterable[Address],msg : T) : Unit = {
        for(r <- recipients)
            channel.send(new Message(r,null,serializer out msg))
    }

    private def broadcast[T <: AnyRef](msg : T) : Unit = channel.send(new Message(null,null,serializer out msg))

   override def receive = {
      case Zombie(x) => { //Ask the presumed zombie for papers and prematurely treat it as dead
                          broadcast(x :: Nil,PapersPlease)
                          remotes = remotes - x
                        }

      case v : View  => {
           log debug v.printDetails
           //Not present in the cluster anymore = presumably zombies
           //Nodes we have no prior knowledge existed = unknowns
           val members = Set[Address]() ++ v.getMembers.asScala
           val zombies = Set[Address]() ++ remotes.keySet -- members
           val unknown : Set[Address] = members -- remotes.keySet

           //Tell the zombies and unknowns to provide papers and prematurely treat the zombies as dead
           broadcast(zombies ++ unknown, PapersPlease)
           remotes = remotes -- zombies
          }

      case m : Message if m.getSrc != channel.getAddress => {
            ( serializer in(m.getRawBuffer,None) ) match {
                case PapersPlease => broadcast(m.getSrc :: Nil,Papers(local.endpoints))
                case Papers(x)    => remotes = remotes + (m.getSrc -> Node(x))
                case unknown      => log debug unknown.toString
            }
          }

      case DeregisterLocalNode(s) => {
           log debug "DeregisterLocalNode"+s
           local = Node(local.endpoints - s)
           broadcast(Papers(local.endpoints))
          }

      case RegisterLocalNode(s)   => {
           log debug "RegisterLocalNode"+s
           local = Node(local.endpoints + s)
           broadcast(Papers(local.endpoints))
          }
      
      case Block                    => log debug "Asked to block" //TODO HotSwap to a buffering body
      case Unblock                  => log debug "Asked to unblock" //TODO HotSwap back and flush the buffer
    }

    def members = remotes.values.toList
    def registerLocalNode(server : RemoteAddress)   : Unit = this ! RegisterLocalNode(server)
    def deregisterLocalNode(server : RemoteAddress) : Unit = this ! DeregisterLocalNode(server)

    override def shutdown = {
      remotes = Map()
      channel.close
    }
}