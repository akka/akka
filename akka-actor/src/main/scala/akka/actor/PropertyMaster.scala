/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.util.NonFatal
import java.lang.reflect.InvocationTargetException

/**
 * The property master is responsible for acquiring all props needed for a
 * performance; in Akka this is the class which is used for reflectively
 * loading all configurable parts of an actor system.
 */
trait PropertyMaster {

  def getClassFor[T: ClassManifest](fqcn: String): Either[Throwable, Class[_ <: T]]

  def getInstanceFor[T: ClassManifest](fqcn: String, args: Seq[(Class[_], AnyRef)]): Either[Throwable, T]

  def getObjectFor[T: ClassManifest](fqcn: String): Either[Throwable, T]

  /**
   * This is needed e.g. by the JavaSerializer to build the ObjectInputStream.
   */
  def classLoader: ClassLoader

}

object PropertyMaster {

  def getInstanceFor[T: ClassManifest](clazz: Class[_], args: Seq[(Class[_], AnyRef)]): Either[Throwable, T] = {
    val types = args.map(_._1).toArray
    val values = args.map(_._2).toArray
    withErrorHandling {
      val constructor = clazz.getDeclaredConstructor(types: _*)
      constructor.setAccessible(true)
      val obj = constructor.newInstance(values: _*).asInstanceOf[T]
      val t = classManifest[T].erasure
      if (t.isInstance(obj)) Right(obj) else Left(new ClassCastException(clazz + " is not a subtype of " + t))
    }
  }

  /**
   * Caught exception is returned as Left(exception).
   * Unwraps `InvocationTargetException` if its getTargetException is an `Exception`.
   * Other `Throwable`, such as `Error` is thrown.
   */
  @inline
  final def withErrorHandling[T](body: ⇒ Either[Throwable, T]): Either[Throwable, T] =
    try body catch {
      case e: InvocationTargetException ⇒
        e.getTargetException match {
          case NonFatal(t) ⇒ Left(t)
          case t           ⇒ throw t
        }
      case NonFatal(e) ⇒ Left(e)
    }

}

class DefaultPropertyMaster(val classLoader: ClassLoader) extends PropertyMaster {

  import PropertyMaster.withErrorHandling

  override def getClassFor[T: ClassManifest](fqcn: String): Either[Throwable, Class[_ <: T]] =
    try {
      val c = classLoader.loadClass(fqcn).asInstanceOf[Class[_ <: T]]
      val t = classManifest[T].erasure
      if (t.isAssignableFrom(c)) Right(c) else Left(new ClassCastException(t + " is not assignable from " + c))
    } catch {
      case NonFatal(e) ⇒ Left(e)
    }

  override def getInstanceFor[T: ClassManifest](fqcn: String, args: Seq[(Class[_], AnyRef)]): Either[Throwable, T] =
    getClassFor(fqcn).fold(Left(_), { c ⇒
      val types = args.map(_._1).toArray
      val values = args.map(_._2).toArray
      withErrorHandling {
        val constructor = c.getDeclaredConstructor(types: _*)
        constructor.setAccessible(true)
        val obj = constructor.newInstance(values: _*)
        val t = classManifest[T].erasure
        if (t.isInstance(obj)) Right(obj) else Left(new ClassCastException(fqcn + " is not a subtype of " + t))
      }
    })

  override def getObjectFor[T: ClassManifest](fqcn: String): Either[Throwable, T] = {
    getClassFor(fqcn).fold(Left(_), { c ⇒
      withErrorHandling {
        val module = c.getDeclaredField("MODULE$")
        module.setAccessible(true)
        val t = classManifest[T].erasure
        module.get(null) match {
          case null                  ⇒ Left(new NullPointerException)
          case x if !t.isInstance(x) ⇒ Left(new ClassCastException(fqcn + " is not a subtype of " + t))
          case x                     ⇒ Right(x.asInstanceOf[T])
        }
      }
    })
  }

}