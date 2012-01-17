/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import akka.actor._
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConversions._
import akka.util.{ Duration, Timeout }
import akka.config.ConfigurationException
import akka.dispatch.Promise

/**
 * A RoutedActorRef is an ActorRef that has a set of connected ActorRef and it uses a Router to
 * send a message to on (or more) of these actors.
 */
private[akka] class RoutedActorRef(_system: ActorSystemImpl, _props: Props, _supervisor: InternalActorRef, _path: ActorPath)
  extends LocalActorRef(
    _system,
    _props.copy(creator = () ⇒ _props.routerConfig.createActor()),
    _supervisor,
    _path) {

  @volatile
  private[akka] var _routees: Vector[ActorRef] = _ // this MUST be initialized during createRoute
  def routees = _routees

  val route = _props.routerConfig.createRoute(_props.copy(routerConfig = NoRouter), actorContext, this)

  def applyRoute(sender: ActorRef, message: Any): Iterable[Destination] = message match {
    case _: AutoReceivedMessage ⇒ Nil
    case Terminated(_)          ⇒ Nil
    case CurrentRoutees ⇒
      sender ! RouterRoutees(_routees)
      Nil
    case _ ⇒
      if (route.isDefinedAt(sender, message)) route(sender, message)
      else Nil
  }

  _routees match {
    case null ⇒ throw new ActorInitializationException("router " + _props.routerConfig + " did not register routees!")
    case x ⇒
      _routees = x // volatile write to publish the route before sending messages
      // subscribe to Terminated messages for all route destinations, to be handled by Router actor
      _routees foreach underlying.watch
  }

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = {
    val s = if (sender eq null) underlying.system.deadLetters else sender

    val msg = message match {
      case Broadcast(m) ⇒ m
      case m            ⇒ m
    }

    applyRoute(s, message) match {
      case Nil  ⇒ super.!(message)(s)
      case refs ⇒ refs foreach (p ⇒ p.recipient.!(msg)(p.sender))
    }
  }
}

/**
 * This trait represents a router factory: it produces the actual router actor
 * and creates the routing table (a function which determines the recipients
 * for each message which is to be dispatched). The resulting RoutedActorRef
 * optimizes the sending of the message so that it does NOT go through the
 * router’s mailbox unless the route returns an empty recipient set.
 *
 * '''Caution:''' This means
 * that the route function is evaluated concurrently without protection by
 * the RoutedActorRef: either provide a reentrant (i.e. pure) implementation or
 * do the locking yourself!
 *
 * '''Caution:''' Please note that the [[akka.routing.Router]] which needs to
 * be returned by `apply()` should not send a message to itself in its
 * constructor or `preStart()` or publish its self reference from there: if
 * someone tries sending a message to that reference before the constructor of
 * RoutedActorRef has returned, there will be a `NullPointerException`!
 */
trait RouterConfig {

  def createRoute(props: Props, actorContext: ActorContext, ref: RoutedActorRef): Route

  def createActor(): Router = new Router {}

  def adaptFromDeploy(deploy: Option[Deploy]): RouterConfig = {
    deploy match {
      case Some(Deploy(_, _, _, NoRouter, _)) ⇒ this
      case Some(Deploy(_, _, _, r, _))        ⇒ r
      case _                                  ⇒ this
    }
  }

  protected def toAll(sender: ActorRef, routees: Iterable[ActorRef]): Iterable[Destination] = routees.map(Destination(sender, _))

  protected def createRoutees(props: Props, context: ActorContext, nrOfInstances: Int, routees: Iterable[String]): Vector[ActorRef] = (nrOfInstances, routees) match {
    case (0, Nil) ⇒ throw new IllegalArgumentException("Insufficient information - missing configuration.")
    case (x, Nil) ⇒ (1 to x).map(_ ⇒ context.actorOf(props))(scala.collection.breakOut)
    case (_, xs)  ⇒ xs.map(context.actorFor(_))(scala.collection.breakOut)
  }

  protected def createAndRegisterRoutees(props: Props, context: ActorContext, nrOfInstances: Int, routees: Iterable[String]): Unit = {
    registerRoutees(context, createRoutees(props, context, nrOfInstances, routees))
  }

  protected def registerRoutees(context: ActorContext, routees: Vector[ActorRef]): Unit = {
    context.self.asInstanceOf[RoutedActorRef]._routees = routees
  }
}

/**
 * Base trait for `Router` actors. Override `receive` to handle custom
 * messages which the corresponding [[akka.actor.RouterConfig]] lets
 * through by returning an empty route.
 */
trait Router extends Actor {

  val ref = self match {
    case x: RoutedActorRef ⇒ x
    case _                 ⇒ throw new ActorInitializationException("Router actor can only be used in RoutedActorRef")
  }

  final def receive = ({

    case Terminated(child) ⇒
      ref._routees = ref._routees filterNot (_ == child)
      if (ref.routees.isEmpty) context.stop(self)

  }: Receive) orElse routerReceive

  def routerReceive: Receive = {
    case _ ⇒
  }
}

/**
 * Used to broadcast a message to all connections in a router; only the
 * contained message will be forwarded, i.e. the `Broadcast(...)`
 * envelope will be stripped off.
 *
 * Router implementations may choose to handle this message differently.
 */
case class Broadcast(message: Any)

/**
 * Sending this message to a router will make it send back its currently used routees.
 * A RouterRoutees message is sent asynchronously to the "requester" containing information
 * about what routees the router is routing over.
 */
case object CurrentRoutees

/**
 * Message used to carry information about what routees the router is currently using.
 */
case class RouterRoutees(routees: Iterable[ActorRef])

/**
 * For every message sent to a router, its route determines a set of destinations,
 * where for each recipient a different sender may be specified; typically the
 * sender should match the sender of the original request, but e.g. the scatter-
 * gather router needs to receive the replies with an AskActorRef instead.
 */
case class Destination(sender: ActorRef, recipient: ActorRef)

/**
 * Routing configuration that indicates no routing.
 * Oxymoron style.
 */
case object NoRouter extends RouterConfig {
  def createRoute(props: Props, actorContext: ActorContext, ref: RoutedActorRef): Route = null
}

/**
 * Router configuration which has no default, i.e. external configuration is required.
 */
case object FromConfig extends RouterConfig {
  def createRoute(props: Props, actorContext: ActorContext, ref: RoutedActorRef): Route =
    throw new ConfigurationException("router " + ref + " needs external configuration from file (e.g. application.conf)")
}

/**
 * Java API: Router configuration which has no default, i.e. external configuration is required.
 */
case class FromConfig() extends RouterConfig {
  def createRoute(props: Props, actorContext: ActorContext, ref: RoutedActorRef): Route =
    throw new ConfigurationException("router " + ref + " needs external configuration from file (e.g. application.conf)")
}

object RoundRobinRouter {
  def apply(routees: Iterable[ActorRef]) = new RoundRobinRouter(routees = routees map (_.path.toString))
}
/**
 * A Router that uses round-robin to select a connection. For concurrent calls, round robin is just a best effort.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the round robin should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RoundRobinRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil) extends RouterConfig with RoundRobinLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the routees to be used.
   * Java API
   */
  def this(t: java.util.Collection[String]) = {
    this(routees = collectionAsScalaIterable(t))
  }
}

trait RoundRobinLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(props: Props, context: ActorContext, ref: RoutedActorRef): Route = {
    createAndRegisterRoutees(props, context, nrOfInstances, routees)

    val next = new AtomicInteger(0)

    def getNext(): ActorRef = {
      ref.routees(next.getAndIncrement % ref.routees.size)
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, ref.routees)
          case msg            ⇒ List(Destination(sender, getNext()))
        }
    }
  }
}

object RandomRouter {
  def apply(routees: Iterable[ActorRef]) = new RandomRouter(routees = routees map (_.path.toString))
}
/**
 * A Router that randomly selects one of the target connections to send a message to.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the random router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class RandomRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil) extends RouterConfig with RandomLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the routees to be used.
   * Java API
   */
  def this(t: java.util.Collection[String]) = {
    this(routees = collectionAsScalaIterable(t))
  }
}

trait RandomLike { this: RouterConfig ⇒

  import java.security.SecureRandom

  def nrOfInstances: Int

  def routees: Iterable[String]

  private val random = new ThreadLocal[SecureRandom] {
    override def initialValue = SecureRandom.getInstance("SHA1PRNG")
  }

  def createRoute(props: Props, context: ActorContext, ref: RoutedActorRef): Route = {
    createAndRegisterRoutees(props, context, nrOfInstances, routees)

    def getNext(): ActorRef = {
      ref.routees(random.get.nextInt(ref.routees.size))
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, ref.routees)
          case msg            ⇒ List(Destination(sender, getNext()))
        }
    }
  }
}

object BroadcastRouter {
  def apply(routees: Iterable[ActorRef]) = new BroadcastRouter(routees = routees map (_.path.toString))
}
/**
 * A Router that uses broadcasts a message to all its connections.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the random router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class BroadcastRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil) extends RouterConfig with BroadcastLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int) = {
    this(nrOfInstances = nr)
  }

  /**
   * Constructor that sets the routees to be used.
   * Java API
   */
  def this(t: java.util.Collection[String]) = {
    this(routees = collectionAsScalaIterable(t))
  }
}

trait BroadcastLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def createRoute(props: Props, context: ActorContext, ref: RoutedActorRef): Route = {
    createAndRegisterRoutees(props, context, nrOfInstances, routees)

    {
      case (sender, message) ⇒ toAll(sender, ref.routees)
    }
  }
}

object ScatterGatherFirstCompletedRouter {
  def apply(routees: Iterable[ActorRef], within: Duration) = new ScatterGatherFirstCompletedRouter(routees = routees map (_.path.toString), within = within)
}
/**
 * Simple router that broadcasts the message to all routees, and replies with the first response.
 * <br>
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical sense as this means
 * that the random router should both create new actors and use the 'routees' actor(s).
 * In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' to during instantiation they will
 * be ignored if the 'nrOfInstances' is defined in the configuration file for the actor being used.
 */
case class ScatterGatherFirstCompletedRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil, within: Duration)
  extends RouterConfig with ScatterGatherFirstCompletedLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   */
  def this(nr: Int, w: Duration) = {
    this(nrOfInstances = nr, within = w)
  }

  /**
   * Constructor that sets the routees to be used.
   * Java API
   */
  def this(t: java.util.Collection[String], w: Duration) = {
    this(routees = collectionAsScalaIterable(t), within = w)
  }
}

trait ScatterGatherFirstCompletedLike { this: RouterConfig ⇒

  def nrOfInstances: Int

  def routees: Iterable[String]

  def within: Duration

  def createRoute(props: Props, context: ActorContext, ref: RoutedActorRef): Route = {
    createAndRegisterRoutees(props, context, nrOfInstances, routees)

    {
      case (sender, message) ⇒
        val provider: ActorRefProvider = context.asInstanceOf[ActorCell].systemImpl.provider
        val asker = provider.ask(Timeout(within)).get
        asker.result.pipeTo(sender)
        toAll(asker, ref.routees)
    }
  }
}
