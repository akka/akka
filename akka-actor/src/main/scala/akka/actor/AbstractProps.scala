/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

import java.lang.reflect.{ Modifier, ParameterizedType, TypeVariable }

import akka.japi.Creator
import akka.util.Reflect

import scala.annotation.varargs
import scala.language.existentials

/**
 *
 * Java API: Factory for Props instances.
 */
private[akka] trait AbstractProps {

  /**
   * INTERNAL API
   */
  private[akka] def validate(clazz: Class[_]) =
    if (Modifier.isAbstract(clazz.getModifiers))
      throw new IllegalArgumentException(s"Actor class [${clazz.getName}] must not be abstract")

  /**
   * Java API: create a Props given a class and its constructor arguments.
   */
  @varargs
  def create(clazz: Class[_], args: AnyRef*): Props = new Props(deploy = Props.defaultDeploy, clazz = clazz, args = args.toList)

  /**
   * Create new Props from the given [[akka.japi.Creator]].
   *
   * You can not use a Java 8 lambda with this method since the generated classes
   * don't carry enough type information.
   *
   * Use the Props.create(actorClass, creator) instead.
   */
  def create[T <: Actor](creator: Creator[T]): Props = {
    val cc = creator.getClass
    if ((cc.getEnclosingClass ne null) && (cc.getModifiers & Modifier.STATIC) == 0)
      throw new IllegalArgumentException("cannot use non-static local Creator to create actors; make it static (e.g. local to a static method) or top-level")
    val ac = classOf[Actor]
    val coc = classOf[Creator[_]]
    val actorClass = Reflect.findMarker(cc, coc) match {
      case t: ParameterizedType ⇒
        t.getActualTypeArguments.head match {
          case c: Class[_] ⇒ c // since T <: Actor
          case v: TypeVariable[_] ⇒
            v.getBounds collectFirst { case c: Class[_] if ac.isAssignableFrom(c) && c != ac ⇒ c } getOrElse ac
          case x ⇒ throw new IllegalArgumentException(s"unsupported type found in Creator argument [$x]")
        }
      case c: Class[_] if (c == coc) ⇒
        throw new IllegalArgumentException(s"erased Creator types are unsupported, use Props.create(actorClass, creator) instead")
    }
    create(classOf[CreatorConsumer], actorClass, creator)
  }

  /**
   * Create new Props from the given [[akka.japi.Creator]] with the type set to the given actorClass.
   */
  def create[T <: Actor](actorClass: Class[T], creator: Creator[T]): Props = {
    create(classOf[CreatorConsumer], actorClass, creator)
  }
}
