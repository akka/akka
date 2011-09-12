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

  def register(provider: ActorRefProvider, providerType: ProviderType) = providerType match {
    case LocalProvider   ⇒ localProvider = Option(provider)
    case RemoteProvider  ⇒ remoteProvider = Option(provider)
    case ClusterProvider ⇒ clusterProvider = Option(provider)
  }

  //FIXME Implement support for configuring by deployment ID etc
  //FIXME If deployId matches an already created actor (Ahead-of-time deployed) return that actor
  //FIXME If deployId exists in config, it will override the specified Props (should we attempt to merge?)

  def actorOf(props: Props, address: String): ActorRef = {

    @annotation.tailrec
    def actorOf(props: Props, address: String, providers: List[ActorRefProvider]): Option[ActorRef] = {
      providers match {
        case Nil ⇒ None
        case provider :: rest ⇒
          provider.actorOf(props, address) match {
            case None ⇒ actorOf(props, address, rest) // recur
            case ref  ⇒ ref
          }
      }
    }

    actorOf(props, address, providersAsList).getOrElse(throw new ActorRefProviderException(
      "Actor [" +
        address +
        "] could not be created or found in any of the registered ActorRefProvider's [" +
        providersAsList.mkString(", ") + "]"))
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

  private[akka] def systemActorOf(props: Props, address: String): Option[ActorRef] = {
    localProvider
      .getOrElse(throw new IllegalStateException("No LocalActorRefProvider available"))
      .asInstanceOf[LocalActorRefProvider]
      .actorOf(props, address, true)
  }

  private def providersAsList = List(localProvider, remoteProvider, clusterProvider).filter(_.isDefined).map(_.get)
}

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider extends ActorRefProvider {

  def actorOf(props: Props, address: String): Option[ActorRef] = actorOf(props, address, false)

  def findActorRef(address: String): Option[ActorRef] = Actor.registry.local.actorFor(address)

  private[akka] def actorOf(props: Props, address: String, systemService: Boolean): Option[ActorRef] = {
    Address.validate(address)

    Actor.registry.actorFor(address) match { // check if the actor for the address is already in the registry
      case ref @ Some(_) ⇒ ref // it is -> return it

      case None ⇒ // it is not -> create it

        // if 'Props.deployId' is not specified then use 'address' as 'deployId'
        val deployId = props.deployId match {
          case Props.`defaultDeployId` | null ⇒ address
          case other                          ⇒ other
        }

        Deployer.lookupDeploymentFor(deployId) match {

          case Some(Deploy(_, _, router, _, Local)) ⇒
            // FIXME create RoutedActorRef if 'router' is specified
            Some(new LocalActorRef(props, address, systemService)) // create a local actor

          case deploy ⇒ None // non-local actor
        }
    }
  }
}

// class ClusterActorRefProvider extends ActorRefProvider {

//   def actorOf(props: Props, address: String): Option[ActorRef] = {
//     deploy match {
//       case Deploy(configAddress, recipe, router, failureDetector, Clustered(preferredHomeNodes, replicas, replication)) ⇒

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
