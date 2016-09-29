/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package adapter

import akka.{ actor ⇒ a }

private[typed] object PropsAdapter {

  // FIXME dispatcher and queue size
  def apply(p: Props[_]): a.Props = new a.Props(a.Deploy(), classOf[ActorAdapter[_]], (p.creator: AnyRef) :: Nil)

  def apply[T](p: a.Props): Props[T] = {
    assert(p.clazz == classOf[ActorAdapter[_]], "typed.Actor must have typed.Props")
    p.args match {
      case (creator: Function0[_]) :: Nil ⇒
        // FIXME queue size
        Props(creator.asInstanceOf[() ⇒ Behavior[T]], DispatcherFromConfig(p.deploy.dispatcher), Int.MaxValue)
      case _ ⇒ throw new AssertionError("typed.Actor args must be right")
    }
  }

}
