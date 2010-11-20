/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.spring

object StringReflect {

  /**
   * Implicit conversion from String to StringReflect.
   */
  implicit def string2StringReflect(x: String) = new StringReflect(x)
}

/**
 * Reflection helper class.
 * @author michaelkober
 */
class StringReflect(val self: String) {
  if ((self eq null) || self == "") throw new IllegalArgumentException("Class name can't be null or empty string [" + self + "]")
  def toClass[T <: AnyRef]: Class[T] = {
    val clazz = Class.forName(self)
    clazz.asInstanceOf[Class[T]]
  }
}
