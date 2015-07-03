package sample.distributeddata

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent
import akka.cluster.ClusterEvent.LeaderChanged
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.GSet
import akka.cluster.ddata.GSetKey
import akka.cluster.ddata.Key
import akka.cluster.ddata.ORSet

object ServiceRegistry {
  import akka.cluster.ddata.Replicator._

  val props: Props = Props[ServiceRegistry]

  /**
   * Register a `service` with a `name`. Several services
   * can be registered with the same `name`.
   * It will be removed when it is terminated.
   */
  final case class Register(name: String, service: ActorRef)
  /**
   * Lookup services registered for a `name`. [[Bindings]] will
   * be sent to `sender()`.
   */
  final case class Lookup(name: String)
  /**
   * Reply for [[Lookup]]
   */
  final case class Bindings(name: String, services: Set[ActorRef])
  /**
   * Published to `ActorSystem.eventStream` when services are changed.
   */
  final case class BindingChanged(name: String, services: Set[ActorRef])

  final case class ServiceKey(serviceName: String) extends Key[ORSet[ActorRef]](serviceName)

  private val AllServicesKey = GSetKey[ServiceKey]("service-keys")

}

class ServiceRegistry extends Actor with ActorLogging {
  import akka.cluster.ddata.Replicator._
  import ServiceRegistry._

  val replicator = DistributedData(context.system).replicator
  implicit val cluster = Cluster(context.system)

  var keys = Set.empty[ServiceKey]
  var services = Map.empty[String, Set[ActorRef]]
  var leader = false

  def serviceKey(serviceName: String): ServiceKey =
    ServiceKey("service:" + serviceName)

  override def preStart(): Unit = {
    replicator ! Subscribe(AllServicesKey, self)
    cluster.subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[ClusterEvent.LeaderChanged])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  def receive = {
    case Register(name, service) ⇒
      val dKey = serviceKey(name)
      // store the service names in a separate GSet to be able to
      // get notifications of new names
      if (!keys(dKey))
        replicator ! Update(AllServicesKey, GSet(), WriteLocal)(_ + dKey)
      // add the service
      replicator ! Update(dKey, ORSet(), WriteLocal)(_ + service)

    case Lookup(name) ⇒
      sender() ! Bindings(name, services.getOrElse(name, Set.empty))

    case c @ Changed(AllServicesKey) ⇒
      val newKeys = c.get(AllServicesKey).elements
      log.debug("Services changed, added: {}, all: {}", (newKeys -- keys), newKeys)
      (newKeys -- keys).foreach { dKey ⇒
        // subscribe to get notifications of when services with this name are added or removed
        replicator ! Subscribe(dKey, self)
      }
      keys = newKeys

    case c @ Changed(ServiceKey(serviceName)) ⇒
      val name = serviceName.split(":").tail.mkString
      val newServices = c.get(serviceKey(name)).elements
      log.debug("Services changed for name [{}]: {}", name, newServices)
      services = services.updated(name, newServices)
      context.system.eventStream.publish(BindingChanged(name, newServices))
      if (leader)
        newServices.foreach(context.watch) // watch is idempotent

    case LeaderChanged(node) ⇒
      // Let one node (the leader) be responsible for removal of terminated services
      // to avoid redundant work and too many death watch notifications.
      // It is not critical to only do it from one node.
      val wasLeader = leader
      leader = node.exists(_ == cluster.selfAddress)
      // when used with many (> 500) services you must increase the system message buffer
      // `akka.remote.system-message-buffer-size`
      if (!wasLeader && leader)
        for (refs ← services.valuesIterator; ref ← refs)
          context.watch(ref)
      else if (wasLeader && !leader)
        for (refs ← services.valuesIterator; ref ← refs)
          context.unwatch(ref)

    case Terminated(ref) ⇒
      val names = services.collect { case (name, refs) if refs.contains(ref) ⇒ name }
      names.foreach { name ⇒
        log.debug("Service with name [{}] terminated: {}", name, ref)
        replicator ! Update(serviceKey(name), ORSet(), WriteLocal)(_ - ref)
      }

    case _: UpdateResponse[_] ⇒ // ok
  }

}
