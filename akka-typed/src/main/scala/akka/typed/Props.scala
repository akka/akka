/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.actor.Deploy
import akka.routing.RouterConfig

/**
 * Props describe how to dress up a [[Behavior]] so that it can become an Actor.
 */
@SerialVersionUID(1L)
final case class Props[T](creator: () ⇒ Behavior[T], deploy: Deploy) {
  def withDispatcher(d: String) = copy(deploy = deploy.copy(dispatcher = d))
  def withMailbox(m: String) = copy(deploy = deploy.copy(mailbox = m))
  def withRouter(r: RouterConfig) = copy(deploy = deploy.copy(routerConfig = r))
  def withDeploy(d: Deploy) = copy(deploy = d)
}

/**
 * Props describe how to dress up a [[Behavior]] so that it can become an Actor.
 */
object Props {
  /**
   * Create a Props instance from a block of code that creates a [[Behavior]].
   *
   * FIXME: investigate the pros and cons of making this take an explicit
   *        function instead of a by-name argument
   */
  def apply[T](block: ⇒ Behavior[T]): Props[T] = Props(() ⇒ block, akka.actor.Props.defaultDeploy)

  /**
   * Props for a Behavior that just ignores all messages.
   */
  def empty[T]: Props[T] = _empty.asInstanceOf[Props[T]]
  private val _empty: Props[Any] = Props(ScalaDSL.Static[Any] { case _ ⇒ ScalaDSL.Unhandled })

  /**
   * INTERNAL API.
   */
  private[typed] def untyped[T](p: Props[T]): akka.actor.Props =
    new akka.actor.Props(p.deploy, classOf[ActorAdapter[_]], p.creator :: Nil)

  /**
   * INTERNAL API.
   */
  private[typed] def apply[T](p: akka.actor.Props): Props[T] = {
    assert(p.clazz == classOf[ActorAdapter[_]], "typed.Actor must have typed.Props")
    p.args match {
      case (creator: Function0[_]) :: Nil ⇒
        Props(creator.asInstanceOf[Function0[Behavior[T]]], p.deploy)
      case _ ⇒ throw new AssertionError("typed.Actor args must be right")
    }
  }
}
