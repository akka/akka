/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorCell
import akka.actor.ActorInitializationException
import akka.actor.ActorSystemImpl
import akka.actor.AutoReceivedMessage
import akka.actor.IndirectActorProducer
import akka.actor.InternalActorRef
import akka.actor.Props
import akka.actor.Terminated
import akka.dispatch.Envelope
import akka.dispatch.MessageDispatcher
import akka.actor.ActorContext
import akka.actor.PoisonPill
import akka.actor.SupervisorStrategy
import akka.actor.ActorRef
import akka.actor.ReceiveTimeout
import akka.actor.Identify
import akka.actor.ActorIdentity

/**
 * INTERNAL API
 */
private[akka] object RoutedActorCell {
  class RouterActorCreator(routerConfig: RouterConfig) extends IndirectActorProducer {
    override def actorClass = classOf[RouterActor]
    override def produce() = routerConfig.createRouterActor()
  }

}

/**
 * INTERNAL API
 */
private[akka] class RoutedActorCell(
  _system: ActorSystemImpl,
  _ref: InternalActorRef,
  _routerProps: Props,
  _routerDispatcher: MessageDispatcher,
  val routeeProps: Props,
  _supervisor: InternalActorRef)
  extends ActorCell(_system, _ref, _routerProps, _routerDispatcher, _supervisor) {

  private[akka] val routerConfig = _routerProps.routerConfig

  @volatile
  private var _router: Router = null // initialized in start, and then only updated from the actor
  def router: Router = _router

  def addRoutee(routee: Routee): Unit = addRoutees(List(routee))

  /**
   * Add routees to the `Router`. Messages in flight may still be routed to
   * the old `Router` instance containing the old routees.
   */
  def addRoutees(routees: immutable.Iterable[Routee]): Unit = {
    routees foreach watch
    val r = _router
    _router = r.withRoutees(r.routees ++ routees)
  }

  def removeRoutee(routee: Routee, stopChild: Boolean): Unit =
    removeRoutees(List(routee), stopChild)

  /**
   * Remove routees from the `Router`. Messages in flight may still be routed to
   * the old `Router` instance containing the old routees.
   */
  def removeRoutees(routees: immutable.Iterable[Routee], stopChild: Boolean): Unit = {
    val r = _router
    val newRoutees = routees.foldLeft(r.routees) { (xs, x) ⇒ unwatch(x); xs.filterNot(_ == x) }
    _router = r.withRoutees(newRoutees)
    if (stopChild) routees foreach stopIfChild
  }

  private def watch(routee: Routee): Unit = routee match {
    case ActorRefRoutee(ref) ⇒ watch(ref)
    case _                   ⇒
  }

  private def unwatch(routee: Routee): Unit = routee match {
    case ActorRefRoutee(ref) ⇒ unwatch(ref)
    case _                   ⇒
  }

  private def stopIfChild(routee: Routee): Unit = routee match {
    case ActorRefRoutee(ref) ⇒ child(ref.path.name) match {
      case Some(`ref`) ⇒
        // The reason for the delay is to give concurrent
        // messages a chance to be placed in mailbox before sending PoisonPill,
        // best effort.
        system.scheduler.scheduleOnce(100.milliseconds, ref, PoisonPill)(dispatcher)
      case _ ⇒
    }
    case _ ⇒
  }

  override def start(): this.type = {
    // create the initial routees before scheduling the Router actor
    _router = routerConfig.createRouter(system)
    routerConfig match {
      case old: DeprecatedRouterConfig ⇒
        if (old.nrOfInstances > 0)
          addRoutees(Vector.fill(old.nrOfInstances)(old.newRoutee(routeeProps, this)))
        val paths = old.paths
        if (paths.nonEmpty)
          addRoutees(paths.map(p ⇒ old.routeeFor(p, this))(collection.breakOut))
      case pool: Pool ⇒
        if (pool.nrOfInstances > 0)
          addRoutees(Vector.fill(pool.nrOfInstances)(pool.newRoutee(routeeProps, this)))
      case group: Group ⇒
        val paths = group.paths
        if (paths.nonEmpty)
          addRoutees(paths.map(p ⇒ group.routeeFor(p, this))(collection.breakOut))
      case _ ⇒
    }
    preSuperStart()
    super.start()
  }

  /**
   * Called when `router` is initalized but before `super.start()` to
   * be able to do extra initialization in subclass.
   */
  protected def preSuperStart(): Unit = ()

  /*
   * end of construction
   */

  /**
   * Route the message via the router to the selected destination.
   *
   * When [[akka.routing.CurrentRoutees]] is sent to the RoutedActorRef it
   * replies with [[akka.routing.RouterRoutees]].
   */
  override def sendMessage(envelope: Envelope): Unit = {
    if (routerConfig.isManagementMessage(envelope.message))
      super.sendMessage(envelope)
    else
      router.route(envelope.message, envelope.sender)
  }

}

/**
 * INTERNAL API
 */
private[akka] class RouterActor extends Actor {

  val cell = context match {
    case x: RoutedActorCell ⇒ x
    case _ ⇒
      throw ActorInitializationException("Router actor can only be used in RoutedActorRef, not in " + context.getClass)
  }

  val routingLogicController: Option[ActorRef] = cell.routerConfig.routingLogicController(
    cell.router.logic).map(props ⇒ context.actorOf(props.withDispatcher(context.props.dispatcher),
      name = "routingLogicController"))

  def receive = {
    case GetRoutees ⇒
      sender() ! Routees(cell.router.routees)
    case CurrentRoutees ⇒
      context.actorOf(Props(classOf[CollectRouteeRefs], cell.router.routees, sender()))
    case AddRoutee(routee) ⇒
      cell.addRoutee(routee)
    case RemoveRoutee(routee) ⇒
      cell.removeRoutee(routee, stopChild = true)
      stopIfAllRouteesRemoved()
    case other if routingLogicController.isDefined ⇒
      routingLogicController.foreach(_.forward(other))
  }

  def stopIfAllRouteesRemoved(): Unit =
    if (cell.router.routees.isEmpty && cell.routerConfig.stopRouterWhenAllRouteesRemoved)
      context.stop(self)

  override def preRestart(cause: Throwable, msg: Option[Any]): Unit = {
    // do not scrap children
  }
}

/**
 * INTERNAL API
 * Backwards compatibility glue to support CurrentRoutees/RouterRoutees containing refs of
 * the routees. This class is not needed when CurrentRoutees/RouterRoutees are removed.
 */
private[akka] class CollectRouteeRefs(routees: immutable.IndexedSeq[Routee], replyTo: ActorRef) extends Actor {

  var collected = Vector.empty[ActorRef]
  var count = 0
  routees.foreach(_.send(Identify(None), self))

  import context.dispatcher
  context.system.scheduler.scheduleOnce(10.seconds, self, ReceiveTimeout)

  def receive = {
    case ActorIdentity(_, refOption) ⇒
      refOption foreach { ref ⇒ collected = collected :+ ref }
      count += 1
      if (count == routees.size) done()
    case ReceiveTimeout ⇒ done()
  }

  def done(): Unit = {
    replyTo ! RouterRoutees(collected)
    context.stop(self)
  }
}

/**
 * INTERNAL API
 */
private[akka] class RouterPoolActor(override val supervisorStrategy: SupervisorStrategy) extends RouterActor {

  val pool = cell.routerConfig match {
    case x: Pool ⇒ x
    case other ⇒
      throw ActorInitializationException("RouterPoolActor can only be used with Pool, not " + other.getClass)
  }

  override def receive = ({
    case Terminated(child) ⇒
      cell.removeRoutee(ActorRefRoutee(child), stopChild = false)
      stopIfAllRouteesRemoved()
    case AdjustPoolSize(change: Int) ⇒
      if (change > 0) {
        val newRoutees = Vector.fill(change)(pool.newRoutee(cell.routeeProps, context))
        cell.addRoutees(newRoutees)
      } else if (change < 0) {
        val currentRoutees = cell.router.routees
        val abandon = currentRoutees.drop(currentRoutees.length + change)
        cell.removeRoutees(abandon, stopChild = true)
      }
  }: Actor.Receive) orElse super.receive

}

