/*
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.configgy

import javax.{management => jmx}
import scala.collection.JavaConversions

class JmxWrapper(node: Attributes) extends jmx.DynamicMBean {

  //private val log = Logger.get

  val operations: Array[jmx.MBeanOperationInfo] = Array(
    new jmx.MBeanOperationInfo("set", "set a string value",
      Array(
        new jmx.MBeanParameterInfo("key", "java.lang.String", "config key"),
        new jmx.MBeanParameterInfo("value", "java.lang.String", "string value")
      ), "void", jmx.MBeanOperationInfo.ACTION),
    new jmx.MBeanOperationInfo("remove", "remove a value",
      Array(
        new jmx.MBeanParameterInfo("key", "java.lang.String", "config key")
      ), "void", jmx.MBeanOperationInfo.ACTION),
    new jmx.MBeanOperationInfo("add_list", "append a value to a list",
      Array(
        new jmx.MBeanParameterInfo("key", "java.lang.String", "config key"),
        new jmx.MBeanParameterInfo("value", "java.lang.String", "value")
      ), "void", jmx.MBeanOperationInfo.ACTION),
    new jmx.MBeanOperationInfo("remove_list", "remove a value to a list",
      Array(
        new jmx.MBeanParameterInfo("key", "java.lang.String", "config key"),
        new jmx.MBeanParameterInfo("value", "java.lang.String", "value")
      ), "void", jmx.MBeanOperationInfo.ACTION)
  )

  def getMBeanInfo() = {
    new jmx.MBeanInfo("akka.configgy.ConfigMap", "configuration node", node.asJmxAttributes(),
      null, operations, null, new jmx.ImmutableDescriptor("immutableInfo=false"))
  }

  def getAttribute(name: String): AnyRef = node.asJmxDisplay(name)

  def getAttributes(names: Array[String]): jmx.AttributeList = {
    val rv = new jmx.AttributeList
    for (name <- names) rv.add(new jmx.Attribute(name, getAttribute(name)))
    rv
  }

  def invoke(actionName: String, params: Array[Object], signature: Array[String]): AnyRef = {
    actionName match {
      case "set" =>
        params match {
          case Array(name: String, value: String) =>
            try {
              node.setString(name, value)
            } catch {
              case e: Exception =>
                //log.warning("exception: %s", e.getMessage)
                throw e
            }
          case _ =>
            throw new jmx.MBeanException(new Exception("bad signature " + params.toList.toString))
        }
      case "remove" =>
        params match {
          case Array(name: String) =>
            node.remove(name)
          case _ =>
            throw new jmx.MBeanException(new Exception("bad signature " + params.toList.toString))
        }
      case "add_list" =>
        params match {
          case Array(name: String, value: String) =>
            node.setList(name, node.getList(name).toList ++ List(value))
          case _ =>
            throw new jmx.MBeanException(new Exception("bad signature " + params.toList.toString))
        }
      case "remove_list" =>
        params match {
          case Array(name: String, value: String) =>
            node.setList(name, node.getList(name).toList - value)
          case _ =>
            throw new jmx.MBeanException(new Exception("bad signature " + params.toList.toString))
        }
      case _ =>
        throw new jmx.MBeanException(new Exception("no such method"))
    }
    null
  }

  def setAttribute(attr: jmx.Attribute): Unit = {
    attr.getValue() match {
      case s: String =>
        node.setString(attr.getName(), s)
      case _ =>
        throw new jmx.InvalidAttributeValueException()
    }
  }

  def setAttributes(attrs: jmx.AttributeList): jmx.AttributeList = {
    for (attr <- JavaConversions.asBuffer(attrs.asList)) setAttribute(attr)
    attrs
  }
}
