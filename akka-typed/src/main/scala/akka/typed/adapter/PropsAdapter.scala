/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package adapter

import akka.{ actor ⇒ a }

private[typed] object PropsAdapter {

  // FIXME dispatcher and queue size
  def apply(b: Behavior[_], deploy: DeploymentConfig): a.Props = new a.Props(a.Deploy(), classOf[ActorAdapter[_]], (b: AnyRef) :: Nil)

  def apply[T](p: a.Props): Behavior[T] = {
    assert(p.clazz == classOf[ActorAdapter[_]], "typed.Actor must have typed.Props")
    p.args match {
      case (initial: Behavior[_]) :: Nil ⇒
        // FIXME queue size
        initial.asInstanceOf[Behavior[T]]
      case _ ⇒ throw new AssertionError("typed.Actor args must be right")
    }
  }

}
