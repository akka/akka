/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.config.ConfigurationException
import akka.util.ReflectiveAccess
import akka.routing._
import akka.AkkaApplication
import java.util.concurrent.ConcurrentHashMap
import com.eaio.uuid.UUID
import akka.AkkaException
import akka.event.{ ActorClassification, DeathWatch, Logging }
import akka.dispatch._

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  def actorOf(props: Props, supervisor: ActorRef, address: String): ActorRef = actorOf(props, supervisor, address, false)

  def actorOf(props: RoutedProps, supervisor: ActorRef, address: String): ActorRef

  def actorFor(address: String): Option[ActorRef]

  /**
   * What deployer will be used to resolve deployment configuration?
   */
  private[akka] def deployer: Deployer

  private[akka] def actorOf(props: Props, supervisor: ActorRef, address: String, systemService: Boolean): ActorRef

  private[akka] def evict(address: String): Boolean

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef]

  private[akka] def serialize(actor: ActorRef): SerializedActorRef

  private[akka] def createDeathWatch(): DeathWatch

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any]

  private[akka] def theOneWhoWalksTheBubblesOfSpaceTime: ActorRef

  private[akka] def terminationFuture: Future[AkkaApplication.ExitStatus]

}

/**
 * Interface implemented by AkkaApplication and AkkaContext, the only two places from which you can get fresh actors
 */
trait ActorRefFactory {

  def provider: ActorRefProvider

  def dispatcher: MessageDispatcher

  /**
   * Father of all children created by this interface.
   */
  protected def guardian: ActorRef

  def actorOf(props: Props): ActorRef = actorOf(props, Props.randomAddress)

  /*
   * TODO this will have to go at some point, because creating two actors with
   * the same address can race on the cluster, and then you never know which
   * implementation wins
   */
  def actorOf(props: Props, address: String): ActorRef = provider.actorOf(props, guardian, address, false)

  def actorOf[T <: Actor](implicit m: Manifest[T]): ActorRef = actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]))

  def actorOf[T <: Actor](address: String)(implicit m: Manifest[T]): ActorRef =
    actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]), address)

  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = actorOf(Props(clazz))

  def actorOf(factory: ⇒ Actor): ActorRef = actorOf(Props(() ⇒ factory))

  def actorOf(creator: UntypedActorFactory): ActorRef = actorOf(Props(() ⇒ creator.create()))

  def actorOf(props: RoutedProps): ActorRef = actorOf(props, Props.randomAddress)

  def actorOf(props: RoutedProps, address: String): ActorRef = provider.actorOf(props, guardian, address)

  def actorFor(address: String): Option[ActorRef] = provider.actorFor(address)

}

class ActorRefProviderException(message: String) extends AkkaException(message)

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider(val app: AkkaApplication) extends ActorRefProvider {

  private[akka] val deployer: Deployer = new Deployer(app)

  val terminationFuture = new DefaultPromise[AkkaApplication.ExitStatus](Timeout.never)(app.dispatcher)
  val log = Logging(app.mainbus, this)

  /**
   * Top-level anchor for the supervision hierarchy of this actor system. Will
   * receive only Supervise/ChildTerminated system messages or Failure message.
   */
  private[akka] val theOneWhoWalksTheBubblesOfSpaceTime: ActorRef = new UnsupportedActorRef {
    @volatile
    var stopped = false

    override def address = app.name + ":BubbleWalker"

    override def toString = address

    def stop() = stopped = true

    def isShutdown = stopped

    protected[akka] override def postMessageToMailbox(msg: Any, sender: ActorRef) {
      msg match {
        case Failed(child, ex)      ⇒ child.stop()
        case ChildTerminated(child) ⇒ terminationFuture.completeWithResult(AkkaApplication.Stopped)
        case _                      ⇒ log.error(this + " received unexpected message " + msg)
      }
    }

    protected[akka] override def sendSystemMessage(message: SystemMessage) {
      message match {
        case Supervise(child) ⇒ // TODO register child in some map to keep track of it and enable shutdown after all dead
        case _                ⇒ log.error(this + " received unexpected system message " + message)
      }
    }
  }

  private val actors = new ConcurrentHashMap[String, AnyRef]

  def actorFor(address: String): Option[ActorRef] = actors.get(address) match {
    case null              ⇒ None
    case actor: ActorRef   ⇒ Some(actor)
    case future: Future[_] ⇒ Some(future.get.asInstanceOf[ActorRef])
  }

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(address: String): Boolean = actors.remove(address) ne null

  private[akka] def actorOf(props: Props, supervisor: ActorRef, address: String, systemService: Boolean): ActorRef = {
    if ((address eq null) || address == Props.randomAddress) {
      val actor = new LocalActorRef(app, props, supervisor, address, systemService = true)
      actors.putIfAbsent(actor.address, actor) match {
        case null  ⇒ actor
        case other ⇒ throw new IllegalStateException("Same uuid generated twice for: " + actor + " and " + other)
      }
    } else {
      val newFuture = Promise[ActorRef](5000)(app.dispatcher) // FIXME is this proper timeout?

      actors.putIfAbsent(address, newFuture) match {
        case null ⇒
          val actor: ActorRef = try {
            (if (systemService) None else deployer.lookupDeployment(address)) match { // see if the deployment already exists, if so use it, if not create actor

              // create a local actor
              case None | Some(DeploymentConfig.Deploy(_, _, DeploymentConfig.Direct, _, DeploymentConfig.LocalScope)) ⇒
                new LocalActorRef(app, props, supervisor, address, systemService) // create a local actor

              // create a routed actor ref
              case deploy @ Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, DeploymentConfig.LocalScope)) ⇒

                val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
                  case RouterType.Direct     ⇒ () ⇒ new DirectRouter
                  case RouterType.Random     ⇒ () ⇒ new RandomRouter
                  case RouterType.RoundRobin ⇒ () ⇒ new RoundRobinRouter
                  case RouterType.ScatterGather ⇒ () ⇒ new ScatterGatherFirstCompletedRouter()(
                    if (props.dispatcher == Props.defaultDispatcher) app.dispatcher else props.dispatcher, app.AkkaConfig.ActorTimeout)
                  case RouterType.LeastCPU          ⇒ sys.error("Router LeastCPU not supported yet")
                  case RouterType.LeastRAM          ⇒ sys.error("Router LeastRAM not supported yet")
                  case RouterType.LeastMessages     ⇒ sys.error("Router LeastMessages not supported yet")
                  case RouterType.Custom(implClass) ⇒ () ⇒ Routing.createCustomRouter(implClass)
                }

                val connections: Iterable[ActorRef] =
                  if (nrOfInstances.factor > 0) Vector.fill(nrOfInstances.factor)(new LocalActorRef(app, props, supervisor, "", systemService)) else Nil

                actorOf(RoutedProps(routerFactory = routerFactory, connectionManager = new LocalConnectionManager(connections)), supervisor, address)

              case unknown ⇒ throw new Exception("Don't know how to create this actor ref! Why? Got: " + unknown)
            }
          } catch {
            case e: Exception ⇒
              newFuture completeWithException e // so the other threads gets notified of error
              //TODO FIXME should we remove the mapping in "actors" here?
              throw e
          }

          newFuture completeWithResult actor
          actors.replace(address, newFuture, actor)
          actor
        case actor: ActorRef ⇒
          actor
        case future: Future[_] ⇒
          future.get.asInstanceOf[ActorRef]
      }
    }

  }

  /**
   * Creates (or fetches) a routed actor reference, configured by the 'props: RoutedProps' configuration.
   */
  def actorOf(props: RoutedProps, supervisor: ActorRef, address: String): ActorRef = {
    // FIXME: this needs to take supervision into account!

    //FIXME clustering should be implemented by cluster actor ref provider
    //TODO Implement support for configuring by deployment ID etc
    //TODO If address matches an already created actor (Ahead-of-time deployed) return that actor
    //TODO If address exists in config, it will override the specified Props (should we attempt to merge?)
    //TODO If the actor deployed uses a different config, then ignore or throw exception?
    if (props.connectionManager.isEmpty) throw new ConfigurationException("RoutedProps used for creating actor [" + address + "] has zero connections configured; can't create a router")
    // val clusteringEnabled = ReflectiveAccess.ClusterModule.isEnabled
    // val localOnly = props.localOnly
    // if (clusteringEnabled && !props.localOnly) ReflectiveAccess.ClusterModule.newClusteredActorRef(props)
    // else new RoutedActorRef(props, address)
    new RoutedActorRef(app, props, address)
  }

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef] = actorFor(actor.address)
  private[akka] def serialize(actor: ActorRef): SerializedActorRef = new SerializedActorRef(actor.address, app.defaultAddress)

  private[akka] def createDeathWatch(): DeathWatch = new LocalDeathWatch

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any] = {
    import akka.dispatch.{ Future, Promise, DefaultPromise }
    (if (within == null) app.AkkaConfig.ActorTimeout else within) match {
      case t if t.duration.length <= 0 ⇒ new DefaultPromise[Any](0)(app.dispatcher) //Abort early if nonsensical timeout
      case t ⇒
        val a = new AskActorRef(app)(timeout = t) { def whenDone() = actors.remove(this) }
        assert(actors.putIfAbsent(a.address, a) eq null) //If this fails, we're in deep trouble
        recipient.tell(message, a)
        a.result
    }
  }
}

class LocalDeathWatch extends DeathWatch with ActorClassification {

  def mapSize = 1024

  override def publish(event: Event): Unit = {
    val monitors = dissociate(classify(event))
    if (monitors.nonEmpty) monitors.foreach(_ ! event)
  }

  override def subscribe(subscriber: Subscriber, to: Classifier): Boolean = {
    if (!super.subscribe(subscriber, to)) {
      subscriber ! Terminated(to)
      false
    } else true
  }
}
