/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.event.EventHandler
import akka.AkkaException
import akka.routing._

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  def actorOf(props: Props, address: String): Option[ActorRef]

  def actorFor(address: String): Option[ActorRef]

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
          provider.actorOf(props, address) match {
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

  def actorFor(address: String): Option[ActorRef] = {

    @annotation.tailrec
    def actorFor(address: String, providers: List[ActorRefProvider]): Option[ActorRef] = {
      providers match {
        case Nil ⇒ None
        case provider :: rest ⇒
          provider.actorFor(address) match {
            case None ⇒ actorFor(address, rest) // recur
            case ref  ⇒ ref
          }
      }
    }

    actorFor(address, providersAsList)
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
  import com.eaio.uuid.UUID

  private val actors = new ConcurrentHashMap[String, Promise[Option[ActorRef]]]

  def actorOf(props: Props, address: String): Option[ActorRef] = actorOf(props, address, false)

  def actorFor(address: String): Option[ActorRef] = actors.get(address) match {
    case null   ⇒ None
    case future ⇒ future.await.resultOrException.getOrElse(None)
  }

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(address: String): Boolean = actors.remove(address) ne null

  private[akka] def actorOf(props: Props, address: String, systemService: Boolean): Option[ActorRef] = {
    Address.validate(address)

    val newFuture = Promise[Option[ActorRef]](5000) // FIXME is this proper timeout?
    val oldFuture = actors.putIfAbsent(address, newFuture)

    if (oldFuture eq null) { // we won the race -- create the actor and resolve the future

      val actor = try {
        Deployer.lookupDeploymentFor(address) match { // see if the deployment already exists, if so use it, if not create actor

          // create a local actor
          case None | Some(DeploymentConfig.Deploy(_, _, DeploymentConfig.Direct, _, _, DeploymentConfig.LocalScope)) ⇒
            Some(new LocalActorRef(props, address, systemService)) // create a local actor

          // create a routed actor ref
          case deploy @ Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, _, DeploymentConfig.LocalScope)) ⇒
            val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
              case RouterType.Direct        ⇒ () ⇒ new DirectRouter
              case RouterType.Random        ⇒ () ⇒ new RandomRouter
              case RouterType.RoundRobin    ⇒ () ⇒ new RoundRobinRouter
              case RouterType.ScatterGather ⇒ () ⇒ new ScatterGatherFirstCompletedRouter
              case RouterType.LeastCPU      ⇒ sys.error("Router LeastCPU not supported yet")
              case RouterType.LeastRAM      ⇒ sys.error("Router LeastRAM not supported yet")
              case RouterType.LeastMessages ⇒ sys.error("Router LeastMessages not supported yet")
              case RouterType.Custom        ⇒ sys.error("Router Custom not supported yet")
            }

            val connections: Iterable[ActorRef] =
              if (nrOfInstances.factor > 0)
                Vector.fill(nrOfInstances.factor)(new LocalActorRef(props, new UUID().toString, systemService))
              else Nil

            Some(Routing.actorOf(RoutedProps(
              routerFactory = routerFactory,
              connectionManager = new LocalConnectionManager(connections))))

          case _ ⇒ None // non-local actor - pass it on
        }
      } catch {
        case e: Exception ⇒
          newFuture completeWithException e // so the other threads gets notified of error
          throw e
      }

      newFuture completeWithResult actor
      actor

    } else { // we lost the race -- wait for future to complete
      oldFuture.await.resultOrException.getOrElse(None)
    }
  }
}
