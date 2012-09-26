/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import scala.util.control.NonFatal
import java.lang.reflect.InvocationTargetException
import scala.reflect.ClassTag
import scala.util.Try

/**
 * The DynamicAccess implementation is the class which is used for
 * loading all configurable parts of an actor system (the
 * [[akka.actor.ReflectiveDynamicAccess]] is the default implementation).
 *
 * This is an internal facility and users are not expected to encounter it
 * unless they are extending Akka in ways which go beyond simple Extensions.
 */
abstract class DynamicAccess {
  /**
   * Convenience method which given a `Class[_]` object and a constructor description
   * will create a new instance of that class.
   *
   * {{{
   * val obj = DynamicAccess.createInstanceFor(clazz, Seq(classOf[Config] -> config, classOf[String] -> name))
   * }}}
   */
  def createInstanceFor[T: ClassTag](clazz: Class[_], args: Seq[(Class[_], AnyRef)]): Try[T]

  /**
   * Obtain a `Class[_]` object loaded with the right class loader (i.e. the one
   * returned by `classLoader`).
   */
  def getClassFor[T: ClassTag](fqcn: String): Try[Class[_ <: T]]

  /**
   * Obtain an object conforming to the type T, which is expected to be
   * instantiated from a class designated by the fully-qualified class name
   * given, where the constructor is selected and invoked according to the
   * `args` argument. The exact usage of args depends on which type is requested,
   * see the relevant requesting code for details.
   */
  def createInstanceFor[T: ClassTag](fqcn: String, args: Seq[(Class[_], AnyRef)]): Try[T]

  /**
   * Obtain the Scala “object” instance for the given fully-qualified class name, if there is one.
   */
  def getObjectFor[T: ClassTag](fqcn: String): Try[T]

  /**
   * This is the class loader to be used in those special cases where the
   * other factory method are not applicable (e.g. when constructing a ClassLoaderBinaryInputStream).
   */
  def classLoader: ClassLoader
}

/**
 * This is the default [[akka.actor.DynamicAccess]] implementation used by [[akka.actor.ActorSystemImpl]]
 * unless overridden. It uses reflection to turn fully-qualified class names into `Class[_]` objects
 * and creates instances from there using `getDeclaredConstructor()` and invoking that. The class loader
 * to be used for all this is determined by the [[akka.actor.ActorSystemImpl]]’s `findClassLoader` method
 * by default.
 */
class ReflectiveDynamicAccess(val classLoader: ClassLoader) extends DynamicAccess {

  override def getClassFor[T: ClassTag](fqcn: String): Try[Class[_ <: T]] =
    Try[Class[_ <: T]]({
      val c = Class.forName(fqcn, true, classLoader).asInstanceOf[Class[_ <: T]]
      val t = implicitly[ClassTag[T]].runtimeClass
      if (t.isAssignableFrom(c)) c else throw new ClassCastException(t + " is not assignable from " + c)
    })

  override def createInstanceFor[T: ClassTag](clazz: Class[_], args: Seq[(Class[_], AnyRef)]): Try[T] =
    Try {
      val types = args.map(_._1).toArray
      val values = args.map(_._2).toArray
      val constructor = clazz.getDeclaredConstructor(types: _*)
      constructor.setAccessible(true)
      val obj = constructor.newInstance(values: _*)
      val t = implicitly[ClassTag[T]].runtimeClass
      if (t.isInstance(obj)) obj.asInstanceOf[T] else throw new ClassCastException(clazz.getName + " is not a subtype of " + t)
    } recover { case i: InvocationTargetException if i.getTargetException ne null ⇒ throw i.getTargetException }

  override def createInstanceFor[T: ClassTag](fqcn: String, args: Seq[(Class[_], AnyRef)]): Try[T] =
    getClassFor(fqcn) flatMap { c ⇒ createInstanceFor(c, args) }

  override def getObjectFor[T: ClassTag](fqcn: String): Try[T] = {
    val classTry =
      if (fqcn.endsWith("$")) getClassFor(fqcn)
      else getClassFor(fqcn + "$") recoverWith { case _ ⇒ getClassFor(fqcn) }
    classTry flatMap { c ⇒
      Try {
        val module = c.getDeclaredField("MODULE$")
        module.setAccessible(true)
        val t = implicitly[ClassTag[T]].runtimeClass
        module.get(null) match {
          case null                  ⇒ throw new NullPointerException
          case x if !t.isInstance(x) ⇒ throw new ClassCastException(fqcn + " is not a subtype of " + t)
          case x: T                  ⇒ x
        }
      } recover { case i: InvocationTargetException if i.getTargetException ne null ⇒ throw i.getTargetException }
    }
  }

}