package akka.remote.netty.rcl

import akka.remote.RemoteMessage
import java.lang.reflect.{ Field, Method }

object ReflectionUtil {

  def getAccessibleField(name: String, clazz: Class[RemoteMessage]): Field = {
    var currentClass: Class[_] = clazz
    while (currentClass != classOf[java.lang.Object]) {
      val declaredField = currentClass.getDeclaredField(name)
      if (declaredField != null) {
        val accessible = declaredField.isAccessible
        try {
          declaredField.setAccessible(true)
          return declaredField
        } finally {
          declaredField.setAccessible(accessible)
        }
      }
      currentClass = currentClass.getSuperclass;
    }
    throw new RuntimeException("Failed to find field " + name + " in " + clazz.getName)
  }

  def setField(name: String, of: AnyRef, withValue: AnyRef) {
    var currentClass: Class[_] = of.getClass
    while (currentClass != classOf[java.lang.Object]) {
      val declaredField = currentClass.getDeclaredField(name)
      if (declaredField != null) {
        val accessible = declaredField.isAccessible
        try {
          declaredField.setAccessible(true)
          declaredField.set(of, withValue)
          return
        } finally {
          declaredField.setAccessible(accessible)
        }
      }
      currentClass = currentClass.getSuperclass;
    }
    throw new RuntimeException("Failed to set field " + name + " on " + of.getClass.getName)
  }

  def getFieldValue(name: String, of: AnyRef): Any = {
    var currentClass: Class[_] = of.getClass
    while (currentClass != classOf[java.lang.Object]) {
      val declaredField = currentClass.getDeclaredField(name)
      if (declaredField != null) {
        val accessible = declaredField.isAccessible
        try {
          declaredField.setAccessible(true)
          return declaredField.get(of)
        } finally {
          declaredField.setAccessible(accessible)
        }
      }
      currentClass = currentClass.getSuperclass;
    }
    throw new RuntimeException("Failed to get field " + name + " on " + of.getClass.getName)
  }

  def invokeMethod(name: String, of: AnyRef): Any = {
    var currentClass: Class[_] = of.getClass
    while (currentClass != classOf[java.lang.Object]) {
      import scala.collection.JavaConversions._
      currentClass.getDeclaredMethods.find(_.getName.equals(name)) match {
        case Some(method) ⇒
          {
            val accessible = method.isAccessible
            try {
              method.setAccessible(true)
              return method.invoke(of)
            } finally {
              method.setAccessible(accessible)
            }
          };
        case _ ⇒ // ignore
      }

      currentClass = currentClass.getSuperclass;
    }
    throw new RuntimeException("Failed to find method " + name + " on " + of.getClass.getName)
  }
}
