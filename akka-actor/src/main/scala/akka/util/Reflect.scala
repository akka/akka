/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util
import scala.util.control.NonFatal
import java.lang.reflect.Constructor
import scala.collection.immutable

/**
 * Collection of internal reflection utilities which may or may not be
 * available (most services specific to HotSpot, but fails gracefully).
 *
 * INTERNAL API
 */
private[akka] object Reflect {

  /**
   * This optionally holds a function which looks N levels above itself
   * on the call stack and returns the `Class[_]` object for the code
   * executing in that stack frame. Implemented using
   * `sun.reflect.Reflection.getCallerClass` if available, None otherwise.
   *
   * Hint: when comparing to Thread.currentThread.getStackTrace, add two levels.
   */
  val getCallerClass: Option[Int ⇒ Class[_]] = {
    try {
      val c = Class.forName("sun.reflect.Reflection");
      val m = c.getMethod("getCallerClass", Array(classOf[Int]): _*)
      Some((i: Int) ⇒ m.invoke(null, Array[AnyRef](i.asInstanceOf[java.lang.Integer]): _*).asInstanceOf[Class[_]])
    } catch {
      case NonFatal(e) ⇒ None
    }
  }

  /**
   * INTERNAL API
   * @param clazz the class which to instantiate an instance of
   * @tparam T the type of the instance that will be created
   * @return a new instance from the default constructor of the given class
   */
  private[akka] def instantiate[T](clazz: Class[T]): T = try clazz.newInstance catch {
    case iae: IllegalAccessException ⇒
      val ctor = clazz.getDeclaredConstructor()
      ctor.setAccessible(true)
      ctor.newInstance()
  }

  /**
   * INTERNAL API
   * Calls findConstructor and invokes it with the given arguments.
   */
  private[akka] def instantiate[T](clazz: Class[T], args: immutable.Seq[Any]): T = {
    val constructor = findConstructor(clazz, args)
    constructor.setAccessible(true)
    try constructor.newInstance(args.asInstanceOf[Seq[AnyRef]]: _*)
    catch {
      case e: IllegalArgumentException ⇒
        val argString = args map (_.getClass) mkString ("[", ", ", "]")
        throw new IllegalArgumentException(s"constructor $constructor is incompatible with arguments $argString", e)
    }
  }

  /**
   * INTERNAL API
   * Implements a primitive form of overload resolution a.k.a. finding the
   * right constructor.
   */
  private[akka] def findConstructor[T](clazz: Class[T], args: immutable.Seq[Any]): Constructor[T] = {
    def error(msg: String): Nothing = {
      val argClasses = args map (_.getClass) mkString ", "
      throw new IllegalArgumentException(s"$msg found on $clazz for arguments [$argClasses]")
    }
    val candidates =
      clazz.getDeclaredConstructors filter (c ⇒
        c.getParameterTypes.length == args.length &&
          (c.getParameterTypes zip args forall {
            case (found, required) ⇒ found.isInstance(required) || BoxedType(found).isInstance(required)
          }))
    if (candidates.size == 1) candidates.head.asInstanceOf[Constructor[T]]
    else if (candidates.size > 1) error("multiple matching constructors")
    else error("no matching constructor")
  }

  /**
   * INTERNAL API
   * @param clazz the class which to instantiate an instance of
   * @tparam T the type of the instance that will be created
   * @return a function which when applied will create a new instance from the default constructor of the given class
   */
  private[akka] def instantiator[T](clazz: Class[T]): () ⇒ T = () ⇒ instantiate(clazz)
}
