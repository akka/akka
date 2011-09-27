/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import DeploymentConfig._
import akka.event.EventHandler
import akka.AkkaException

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  def actorOf(props: Props, address: String): Option[ActorRef]

  def findActorRef(address: String): Option[ActorRef]

  private[akka] def evict(address: String): Boolean
}

class ActorRefProviderException(message: String) extends AkkaException(message)

object ActorRefProvider {
  sealed trait ProviderType
  object LocalProvider extends ProviderType
  object RemoteProvider extends ProviderType
  object ClusterProvider extends ProviderType
}

/**
 * Container for all ActorRef providers.
 */
private[akka] class ActorRefProviders(
  @volatile private var localProvider: Option[ActorRefProvider] = Some(new LocalActorRefProvider),
  @volatile private var remoteProvider: Option[ActorRefProvider] = None,
  @volatile private var clusterProvider: Option[ActorRefProvider] = None) {

  import ActorRefProvider._

  def register(providerType: ProviderType, provider: ActorRefProvider) = {
    EventHandler.info(this, "Registering ActorRefProvider [%s]".format(provider.getClass.getName))
    providerType match {
      case LocalProvider   ⇒ localProvider = Option(provider)
      case RemoteProvider  ⇒ remoteProvider = Option(provider)
      case ClusterProvider ⇒ clusterProvider = Option(provider)
    }
  }

  //FIXME Implement support for configuring by deployment ID etc
  //FIXME If address matches an already created actor (Ahead-of-time deployed) return that actor
  //FIXME If address exists in config, it will override the specified Props (should we attempt to merge?)

  def actorOf(props: Props, address: String): ActorRef = {

    @annotation.tailrec
    def actorOf(props: Props, address: String, providers: List[ActorRefProvider]): Option[ActorRef] = {
      providers match {
        case Nil ⇒ None
        case provider :: rest ⇒
          provider.actorOf(props, address) match { //WARNING FIXME RACE CONDITION NEEDS TO BE SOLVED
            case None ⇒ actorOf(props, address, rest) // recur
            case ref  ⇒ ref
          }
      }
    }

    actorOf(props, address, providersAsList).getOrElse(throw new ActorRefProviderException(
      "Actor [" +
        address +
        "] could not be found in or created by any of the registered 'ActorRefProvider's [" +
        providersAsList.map(_.getClass.getName).mkString(", ") + "]"))
  }

  def findActorRef(address: String): Option[ActorRef] = {

    @annotation.tailrec
    def findActorRef(address: String, providers: List[ActorRefProvider]): Option[ActorRef] = {
      providers match {
        case Nil ⇒ None
        case provider :: rest ⇒
          provider.findActorRef(address) match {
            case None ⇒ findActorRef(address, rest) // recur
            case ref  ⇒ ref
          }
      }
    }

    findActorRef(address, providersAsList)
  }

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(address: String): Boolean = {

    @annotation.tailrec
    def evict(address: String, providers: List[ActorRefProvider]): Boolean = {
      providers match {
        case Nil ⇒ false
        case provider :: rest ⇒
          if (provider.evict(address)) true // done
          else evict(address, rest) // recur
      }
    }

    evict(address, providersAsList)
  }

  private[akka] def systemActorOf(props: Props, address: String): Option[ActorRef] = {
    localProvider
      .getOrElse(throw new IllegalStateException("No LocalActorRefProvider available"))
      .asInstanceOf[LocalActorRefProvider]
      .actorOf(props, address, true)
  }

  private def providersAsList = List(localProvider, remoteProvider, clusterProvider).flatten
}

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider extends ActorRefProvider {
  import java.util.concurrent.ConcurrentHashMap
  import akka.dispatch.Promise

  // FIXME who evicts this registry, and when? Should it be used instead of ActorRegistry?
  private val actors = new ConcurrentHashMap[String, Promise[Option[ActorRef]]]

  def actorOf(props: Props, address: String): Option[ActorRef] = actorOf(props, address, false)

  def findActorRef(address: String): Option[ActorRef] = Actor.registry.local.actorFor(address)

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(address: String): Boolean = actors.remove(address) ne null

  private[akka] def actorOf(props: Props, address: String, systemService: Boolean): Option[ActorRef] = {
    Address.validate(address)

    val newFuture = Promise[Option[ActorRef]](5000) // FIXME is this proper timeout?
    val oldFuture = actors.putIfAbsent(address, newFuture)

    if (oldFuture eq null) { // we won the race -- create the actor and resolve the future
      def newActor() = Some(new LocalActorRef(props, address, systemService))

      val actor =
        Deployer.lookupDeploymentFor(address) match { // see if the deployment already exists, if so use it, if not create actor
          case Some(Deploy(_, _, router, _, LocalScope)) ⇒ newActor() // create a local actor
          case None                                      ⇒ newActor() // create a local actor
          case _                                         ⇒ None // non-local actor
        }

      actor foreach { a ⇒ Actor.registry.register(a) }
      newFuture.completeWithResult(actor)
      actor

    } else { // we lost the race -- wait for future to complete
      oldFuture.await.result.getOrElse(None)
    }
  }
}

// class ClusterActorRefProvider extends ActorRefProvider {

//   def actorOf(props: Props, address: String): Option[ActorRef] = {
//     deploy match {
//       case Deploy(configAddress, recipe, router, failureDetector, Cluster(preferredHomeNodes, replicas, replication)) ⇒

//         ClusterModule.ensureEnabled()

//         if (configAddress != address) throw new IllegalStateException("Deployment config for [" + address + "] is wrong [" + deploy + "]")
//         if (!remote.isRunning) throw new IllegalStateException("Remote server is not running")

//         val isHomeNode = DeploymentConfig.isHomeNode(preferredHomeNodes)

//         val serializer = recipe match {
//           case Some(r) ⇒ Serialization.serializerFor(r.implementationClass)
//           case None    ⇒ Serialization.serializerFor(classOf[Actor]) //FIXME revisit this decision of default
//         }

//         def storeActorAndGetClusterRef(replicationScheme: ReplicationScheme, serializer: Serializer): ActorRef = {
//           // add actor to cluster registry (if not already added)
//           if (!cluster.isClustered(address)) //WARNING!!!! Racy
//             cluster.store(address, factory, replicas.factor, replicationScheme, false, serializer)

//           // remote node (not home node), check out as ClusterActorRef
//           cluster.ref(address, DeploymentConfig.routerTypeFor(router), DeploymentConfig.failureDetectorTypeFor(failureDetector))
//         }

//         replication match {
//           case _: Transient | Transient ⇒
//             storeActorAndGetClusterRef(Transient, serializer)

//           case replication: Replication ⇒
//             if (DeploymentConfig.routerTypeFor(router) != akka.routing.RouterType.Direct) throw new ConfigurationException(
//               "Can't replicate an actor [" + address + "] configured with another router than \"direct\" - found [" + router + "]")

//             if (isHomeNode) { // stateful actor's home node
//               cluster.use(address, serializer)
//                 .getOrElse(throw new ConfigurationException(
//                   "Could not check out actor [" + address + "] from cluster registry as a \"local\" actor"))

//             } else {
//               storeActorAndGetClusterRef(replication, serializer)
//             }
//         }

//       case invalid ⇒ throw new IllegalActorStateException(
//         "Could not create actor with address [" + address + "], not bound to a valid deployment scheme [" + invalid + "]")
//     }
//   }

//   def findActorRef(address: String): Option[ActorRef]
// }
