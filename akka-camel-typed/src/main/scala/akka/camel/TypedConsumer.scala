/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import java.lang.reflect.Method
import java.lang.reflect.Proxy._

import akka.actor.{ LocalActorRef, TypedActor, ActorRef }
import akka.actor.TypedActor._

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
  def withTypedConsumer[T](actorRef: ActorRef, typedActor: Option[AnyRef])(f: (AnyRef, Method) ⇒ T): List[T] = {
    typedActor match {
      case None ⇒ Nil
      case Some(tc) ⇒ {
        withConsumeAnnotatedMethodsOnInterfaces(tc, f) ++
          withConsumeAnnotatedMethodsonImplClass(tc, actorRef, f)
      }
    }
  }

  private implicit def class2ProxyClass(c: Class[_]) = new ProxyClass(c)

  private def withConsumeAnnotatedMethodsOnInterfaces[T](tc: AnyRef, f: (AnyRef, Method) ⇒ T): List[T] = for {
    i ← tc.getClass.allInterfaces
    m ← i.getDeclaredMethods.toList
    if (m.isAnnotationPresent(classOf[consume]))
  } yield f(tc, m)

  private def withConsumeAnnotatedMethodsonImplClass[T](tc: AnyRef, actorRef: ActorRef, f: (AnyRef, Method) ⇒ T): List[T] = actorRef match {
    case l: LocalActorRef ⇒
      val implClass = l.underlyingActorInstance.asInstanceOf[TypedActor.TypedActor[AnyRef, AnyRef]].me.getClass
      for (m ← implClass.getDeclaredMethods.toList; if (m.isAnnotationPresent(classOf[consume]))) yield f(tc, m)
    case _ ⇒ Nil
  }

  private class ProxyClass(c: Class[_]) {
    def allInterfaces: List[Class[_]] = allInterfaces(c.getInterfaces.toList)
    def allInterfaces(is: List[Class[_]]): List[Class[_]] = is match {
      case Nil     ⇒ Nil
      case x :: xs ⇒ x :: allInterfaces(x.getInterfaces.toList) ::: allInterfaces(xs)
    }
  }
}
