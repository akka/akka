/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import DeploymentConfig._
import akka.event.EventHandler
import akka.AkkaException
import akka.routing._
import akka.AkkaApplication
import akka.dispatch.MessageDispatcher
import java.util.concurrent.ConcurrentHashMap
import akka.dispatch.Promise
import com.eaio.uuid.UUID

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  def actorOf(props: Props, address: String): Option[ActorRef]

  def findActorRef(address: String): Option[ActorRef]

  private[akka] def evict(address: String): Boolean
}

/**
 * Interface implemented by AkkaApplication and AkkaContext, the only two places from which you can get fresh actors
 */
trait ActorRefFactory {

  def provider: ActorRefProvider

  def dispatcher: MessageDispatcher

  def createActor(props: Props): ActorRef = createActor(props, new UUID().toString)

  /*
   * TODO this will have to go at some point, because creating two actors with 
   * the same address can race on the cluster, and then you never know which 
   * implementation wins 
   */
  def createActor(props: Props, address: String): ActorRef = {
    val p =
      if (props.dispatcher == Props.defaultDispatcher)
        props.copy(dispatcher = dispatcher)
      else
        props
    provider.actorOf(p, address).get
  }

  def createActor[T <: Actor](implicit m: Manifest[T]): ActorRef = createActor(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]))

  def createActor[T <: Actor](clazz: Class[T]): ActorRef = createActor(Props(clazz))

  def createActor(factory: ⇒ Actor): ActorRef = createActor(Props(() ⇒ factory))

  def createActor(creator: UntypedActorFactory): ActorRef = createActor(Props(() ⇒ creator.create()))

  def findActor(address: String): Option[ActorRef] = provider.findActorRef(address)

}

class ActorRefProviderException(message: String) extends AkkaException(message)

object ActorRefProvider {
  sealed trait ProviderType
  object LocalProvider extends ProviderType
  object RemoteProvider extends ProviderType
  object ClusterProvider extends ProviderType
}

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider(val application: AkkaApplication, val deployer: Deployer) extends ActorRefProvider {

  import application.dispatcher

  private val actors = new ConcurrentHashMap[String, Promise[Option[ActorRef]]]

  def actorOf(props: Props, address: String): Option[ActorRef] = actorOf(props, address, false)

  def findActorRef(address: String): Option[ActorRef] = application.registry.local.actorFor(address)

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
        deployer.lookupDeploymentFor(address) match { // see if the deployment already exists, if so use it, if not create actor

          // create a local actor
          case None | Some(Deploy(_, _, Direct, _, _, LocalScope)) ⇒
            Some(new LocalActorRef(application, props, address, systemService)) // create a local actor

          // create a routed actor ref
          case deploy @ Some(Deploy(_, _, router, nrOfInstances, _, LocalScope)) ⇒
            val routerType = DeploymentConfig.routerTypeFor(router)

            val routerFactory: () ⇒ Router = routerType match {
              case RouterType.Direct        ⇒ () ⇒ new DirectRouter
              case RouterType.Random        ⇒ () ⇒ new RandomRouter
              case RouterType.RoundRobin    ⇒ () ⇒ new RoundRobinRouter
              case RouterType.LeastCPU      ⇒ sys.error("Router LeastCPU not supported yet")
              case RouterType.LeastRAM      ⇒ sys.error("Router LeastRAM not supported yet")
              case RouterType.LeastMessages ⇒ sys.error("Router LeastMessages not supported yet")
              case RouterType.Custom        ⇒ sys.error("Router Custom not supported yet")
            }
            val connections: Iterable[ActorRef] =
              if (nrOfInstances.factor > 0)
                Vector.fill(nrOfInstances.factor)(new LocalActorRef(application, props, new UUID().toString, systemService))
              else Nil

            Some(application.routing.actorOf(RoutedProps(
              routerFactory = routerFactory,
              connections = connections)))

          case _ ⇒ None // non-local actor - pass it on
        }
      } catch {
        case e: Exception ⇒
          newFuture completeWithException e // so the other threads gets notified of error
          throw e
      }

      actor foreach application.registry.register // only for ActorRegistry backward compat, will be removed later

      newFuture completeWithResult actor
      actor

    } else { // we lost the race -- wait for future to complete
      oldFuture.await.resultOrException.getOrElse(None)
    }
  }
}
