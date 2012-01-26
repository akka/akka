/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.io.{ InputStream, ObjectInputStream, ObjectStreamClass }

class ClassLoaderObjectInputStream(classLoader: ClassLoader, is: InputStream) extends ObjectInputStream(is) {
  override protected def resolveClass(objectStreamClass: ObjectStreamClass): Class[_] = {
    Class.forName(objectStreamClass.getName, false, classLoader) match {
      case null  ⇒ super.resolveClass(objectStreamClass)
      case clazz ⇒ clazz
    }
  }
}
