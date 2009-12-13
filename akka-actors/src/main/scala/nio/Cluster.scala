package se.scalablesolutions.akka.nio

import se.scalablesolutions.akka.Config.config
import se.scalablesolutions.akka.util.Logging
import org.jgroups.{JChannel,View,Address,Message,ExtendedMembershipListener,Receiver,SetStateEvent}
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.config.ScalaConfig._
import scala.collection.immutable.{Map,HashMap,HashSet}
import org.jgroups.util.Util
import se.scalablesolutions.akka.actor.{Init,SupervisorFactory,Actor,ActorRegistry}
import se.scalablesolutions.akka.nio.Cluster.{Node,RelayedMessage}

trait Cluster {
    def members : List[Node]
    def name : String
    def registerLocalNode(hostname : String, port : Int)   : Unit
    def deregisterLocalNode(hostname : String, port : Int) : Unit
    def relayMessage(to : Class[_ <: Actor],msg : AnyRef) : Unit
}

abstract class ClusterActor(val name : String) extends Actor with Cluster

object Cluster extends Cluster {
      case class Node(endpoints : List[RemoteAddress])
      case class RelayedMessage(actorClass : Class[_ <: Actor],msg : AnyRef)

      lazy val impl : Option[ClusterActor] = {
        config.getString("akka.remote.cluster.actor") map ( name => {
             val actor = Class.forName(name)
                              .getDeclaredConstructor(Array(classOf[String]): _*)
                              .newInstance(config.getString("akka.remote.cluster.name") getOrElse "default")
                              .asInstanceOf[ClusterActor]

            SupervisorFactory(
                    SupervisorConfig(
                           RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
                                Supervise(actor, LifeCycle(Permanent)):: Nil
                          )
                     ).newInstance.start
            actor !! Init(None) // FIXME for some reason the actor isn't init:ed here
            actor
        })
      }
      
      def name = impl.map(_.name).getOrElse("No cluster")
      def members = impl.map(_.members).getOrElse(Nil)
      def registerLocalNode(hostname : String, port : Int)   : Unit = impl.map(_.registerLocalNode(hostname,port))
      def deregisterLocalNode(hostname : String, port : Int) : Unit = impl.map(_.deregisterLocalNode(hostname,port))
      def relayMessage(to : Class[_ <: Actor],msg : AnyRef)  : Unit = impl.map(_.send(to,msg))
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
    private var channel : Option[JChannel]  = None
    private var remotes : Map[Address,Node] = Map()
  
    override def init(config : AnyRef) = {
      log info "Initiating cluster actor"
      remotes = new HashMap[Address,Node]
      val me  = this
      channel = Some(new JChannel {
        setReceiver(new Receiver with ExtendedMembershipListener {
                      def getState : Array[Byte]               = null
                      def setState(state : Array[Byte]) : Unit = ()
                      def receive(msg : Message)        : Unit = me ! msg
                      def viewAccepted(view : View)     : Unit = me ! view
                      def suspect(a : Address)          : Unit = me ! Zombie(a)
                      def block                         : Unit = me ! Block
                      def unblock                       : Unit = me ! Unblock
                   })
      })
      channel.map(_.connect(name))
    }

    protected def serializer = Serializer.Java //FIXME make this configurable
    def members = remotes.values.toList //FIXME We probably want to make this a !! InstalledRemotes

    def registerLocalNode(hostname : String, port : Int)   : Unit = this ! RegisterLocalNode(RemoteAddress(hostname,port))
    def deregisterLocalNode(hostname : String, port : Int) : Unit = this ! DeregisterLocalNode(RemoteAddress(hostname,port))
    def relayMessage(to : Class[_ <: Actor],msg : AnyRef)  : Unit = this ! RelayedMessage(to,msg)
    
    private def broadcast[T <: AnyRef](recipients : Iterable[Address],msg : T) : Unit =
        for(c <- channel; r <- recipients) c.send(new Message(r,null,serializer out msg))

    private def broadcast[T <: AnyRef](msg : T) : Unit =
        channel.map( _.send(new Message(null,null,serializer out msg)))

   override def receive = {
      case Zombie(x) => { //Ask the presumed zombie for papers and prematurely treat it as dead
                          log info "Zombie: "+x
                          broadcast(x :: Nil,PapersPlease)
                          remotes = remotes - x
                        }

      case v : View  => {
           log info v.printDetails
           //Not present in the cluster anymore = presumably zombies
           //Nodes we have no prior knowledge existed = unknowns
           val members = Set[Address]() ++ v.getMembers.asScala - channel.get.getAddress //Exclude ourselves
           val zombies = Set[Address]() ++ remotes.keySet -- members
           val unknown = members -- remotes.keySet

           log info "Updating view, zombies: " + zombies
           log info "             , unknown: " + unknown
           log info "             , members: " + members
           log info "             ,   known: " + remotes.keySet

           //Tell the zombies and unknowns to provide papers and prematurely treat the zombies as dead
           broadcast(zombies ++ unknown, PapersPlease)
           remotes = remotes -- zombies
          }

      case m : Message => {
            if(m.getSrc != channel.map(_.getAddress).getOrElse(m.getSrc)) //handle non-own messages only, and only if we're connected
                (serializer in(m.getRawBuffer,None)) match {
                    case PapersPlease        => {
                            log info "Asked for papers by " + m.getSrc
                            broadcast(m.getSrc :: Nil,Papers(local.endpoints))
                        }
                    case Papers(x)           => {
                            log info "Got papers from " + m.getSrc
                            remotes = remotes + (m.getSrc -> Node(x))
                            log info "Installed nodes: " + remotes.keySet
                        }
                    case RelayedMessage(c,m) => {
                            log info "Relaying [" + m + "] to ["+c.getName+"]"
                            ActorRegistry.actorsFor(c).firstOption.map(_ ! m)
                        }
                    case unknown             => log info "Unknown message: "+unknown.toString
                }
          }

      case rm@RelayedMessage => {
            log info "Relaying message: " + rm
            broadcast(rm)
          }

      case RegisterLocalNode(s)   => {
           log info "RegisterLocalNode: "+s
           local = Node(local.endpoints + s)
           broadcast(Papers(local.endpoints))
          }

      case DeregisterLocalNode(s) => {
           log info "DeregisterLocalNode: "+s
           local = Node(local.endpoints - s)
           broadcast(Papers(local.endpoints))
          }
      
      case Block                    => log info "UNSUPPORTED: block" //TODO HotSwap to a buffering body
      case Unblock                  => log info "UNSUPPORTED: unblock" //TODO HotSwap back and flush the buffer
    }

    override def shutdown = {
      log info "Shutting down "+this.getClass.getName
      channel.map(_.close)
      remotes = Map()
      channel = None
    }
}