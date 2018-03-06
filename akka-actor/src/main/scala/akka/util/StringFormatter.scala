/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.util

object StringFormatter {
  /**
   * If `arg` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   */
  def formatArray(t: String, arg: Any): String = arg match {
    case a: Array[_] if !a.getClass.getComponentType.isPrimitive ⇒ format(t, a: _*)
    case a: Array[_] ⇒ format(t, a.map(_.asInstanceOf[AnyRef]): _*)
    case x ⇒ format(t, x)
  }

  def format(t: String, arg: Any*): String = {
    val sb = new java.lang.StringBuilder(64)
    var p = 0
    var startIndex = 0
    while (p < arg.length) {
      val index = t.indexOf("{}", startIndex)
      if (index == -1) {
        sb.append(t.substring(startIndex, t.length))
          .append(" WARNING arguments left: ")
          .append(arg.length - p)
        p = arg.length
        startIndex = t.length
      } else {
        sb.append(t.substring(startIndex, index))
          .append(arg(p))
        startIndex = index + 2
        p += 1
      }
    }
    sb.append(t.substring(startIndex, t.length)).toString
  }
}
