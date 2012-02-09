/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

object Reflect {

  val getCallerClass: Option[Int ⇒ Class[_]] = {
    try {
      val c = Class.forName("sun.reflect.Reflection");
      val m = c.getMethod("getCallerClass", Array(classOf[Int]): _*)
      Some((i: Int) ⇒ m.invoke(null, Array[AnyRef](i.asInstanceOf[Integer]): _*).asInstanceOf[Class[_]])
    } catch {
      case NonFatal(e) ⇒ None
    }
  }

}