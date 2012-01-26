/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.lang.reflect.InvocationTargetException

object ReflectiveAccess {

  val loader = getClass.getClassLoader
  val noParams: Array[Class[_]] = Array()
  val noArgs: Array[AnyRef] = Array()

  def createInstance[T](clazz: Class[_],
                        params: Array[Class[_]],
                        args: Array[AnyRef]): Either[Exception, T] = withErrorHandling {
    assert(clazz ne null)
    assert(params ne null)
    assert(args ne null)
    val ctor = clazz.getDeclaredConstructor(params: _*)
    ctor.setAccessible(true)
    Right(ctor.newInstance(args: _*).asInstanceOf[T])
  }

  def createInstance[T](fqn: String,
                        params: Array[Class[_]],
                        args: Array[AnyRef],
                        classloader: ClassLoader = loader): Either[Exception, T] = withErrorHandling {
    assert(params ne null)
    assert(args ne null)
    getClassFor(fqn, classloader) match {
      case Right(value) ⇒
        val ctor = value.getDeclaredConstructor(params: _*)
        ctor.setAccessible(true)
        Right(ctor.newInstance(args: _*).asInstanceOf[T])
      case Left(exception) ⇒ Left(exception) //We could just cast this to Either[Exception, T] but it's ugly
    }
  }

  //Obtains a reference to fqn.MODULE$
  def getObjectFor[T](fqn: String, classloader: ClassLoader = loader): Either[Exception, T] = try {
    getClassFor(fqn, classloader) match {
      case Right(value) ⇒
        val instance = value.getDeclaredField("MODULE$")
        instance.setAccessible(true)
        val obj = instance.get(null)
        if (obj eq null) Left(new NullPointerException) else Right(obj.asInstanceOf[T])
      case Left(exception) ⇒ Left(exception) //We could just cast this to Either[Exception, T] but it's ugly
    }
  } catch {
    case e: Exception ⇒
      Left(e)
  }

  def getClassFor[T](fqn: String, classloader: ClassLoader = loader): Either[Exception, Class[T]] = try {
    assert(fqn ne null)

    // First, use the specified CL
    val first = try {
      Right(classloader.loadClass(fqn).asInstanceOf[Class[T]])
    } catch {
      case c: ClassNotFoundException ⇒ Left(c)
    }

    if (first.isRight) first
    else {
      // Second option is to use the ContextClassLoader
      val second = try {
        Right(Thread.currentThread.getContextClassLoader.loadClass(fqn).asInstanceOf[Class[T]])
      } catch {
        case c: ClassNotFoundException ⇒ Left(c)
      }

      if (second.isRight) second
      else {
        val third = try {
          if (classloader ne loader) Right(loader.loadClass(fqn).asInstanceOf[Class[T]]) else Left(null) //Horrid
        } catch {
          case c: ClassNotFoundException ⇒ Left(c)
        }

        if (third.isRight) third
        else {
          try {
            Right(Class.forName(fqn).asInstanceOf[Class[T]]) // Last option is Class.forName
          } catch {
            case c: ClassNotFoundException ⇒ Left(c)
          }
        }
      }
    }
  } catch {
    case e: Exception ⇒ Left(e)
  }

  /**
   * Caught exception is returned as Left(exception).
   * Unwraps `InvocationTargetException` if its getTargetException is an `Exception`.
   * Other `Throwable`, such as `Error` is thrown.
   */
  @inline
  private final def withErrorHandling[T](body: ⇒ Either[Exception, T]): Either[Exception, T] = {
    try {
      body
    } catch {
      case e: InvocationTargetException ⇒ e.getTargetException match {
        case t: Exception ⇒ Left(t)
        case t            ⇒ throw t
      }
      case e: Exception ⇒
        Left(e)
    }
  }

}

