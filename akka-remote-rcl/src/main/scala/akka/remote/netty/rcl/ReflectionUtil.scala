package akka.remote.netty.rcl

import java.lang.reflect.Field
import akka.util.NonFatal

object ReflectionUtil {

  def getField(name: String, klass: Class[_]): Field = {
    try {
      klass.getDeclaredField(name)
    } catch {
      case NonFatal(_) ⇒ getField(name, klass.getSuperclass)
    }
  }

  def getFieldValue(fieldName: String, onInstance: AnyRef): AnyRef = {
    getField(fieldName, onInstance.getClass) match {
      case f: Field ⇒
        f.setAccessible(true)
        f.get(onInstance)
    }
  }

  def setFieldValue(fieldName: String, onInstance: AnyRef, setTo: Any) {
    getField(fieldName, onInstance.getClass) match {
      case f: Field ⇒
        f.setAccessible(true)
        f.set(onInstance, setTo)
    }
  }

  def invokeMethod(methodName: String, onInstance: AnyRef): AnyRef = {
    onInstance.getClass.getMethods.find(_.getName == methodName).get.invoke(onInstance)
  }

}