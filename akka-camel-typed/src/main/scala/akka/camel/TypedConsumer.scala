/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.camel

import java.lang.reflect.Method

import akka.actor.{TypedActor, ActorRef}

/**
 * @author Martin Krasser
 */
private[camel] object TypedConsumer {
  /**
   * Applies a function <code>f</code> to <code>actorRef</code> if <code>actorRef</code>
   * references a typed consumer actor. A valid reference to a typed consumer actor is a
   * local actor reference with a target actor that implements <code>TypedActor</code> and
   * has at least one of its methods annotated with <code>@consume</code> (on interface or
   * implementation class). For each <code>@consume</code>-annotated method, <code>f</code>
   * is called with the corresponding <code>method</code> instance and the return value is
   * added to a list which is then returned by this method.
   */
  def withTypedConsumer[T](actorRef: ActorRef)(f: Method => T): List[T] = {
    if (!actorRef.actor.isInstanceOf[TypedActor]) Nil
    else if (actorRef.homeAddress.isDefined) Nil
    else {
      val typedActor = actorRef.actor.asInstanceOf[TypedActor]
      // TODO: support consumer annotation inheritance
      // - visit overridden methods in superclasses
      // - visit implemented method declarations in interfaces
      val intfClass = typedActor.proxy.getClass
      val implClass = typedActor.getClass
      (for (m <- intfClass.getMethods.toList; if (m.isAnnotationPresent(classOf[consume]))) yield f(m)) ++
      (for (m <- implClass.getMethods.toList; if (m.isAnnotationPresent(classOf[consume]))) yield f(m))
    }
  }
}
