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

import java.net.InetAddress
import scala.collection.{immutable, mutable}
import scala.collection.JavaConversions

/**
 * A ConfigMap that wraps the system environment. This is used as a
 * fallback when looking up "$(...)" substitutions in config files.
 */
private[configgy] object EnvironmentAttributes extends ConfigMap {

  private val env = immutable.Map.empty[String, String] ++ (JavaConversions.asMap(System.getenv()).elements)

  // deal with java.util.Properties extending
  // java.util.Hashtable[Object, Object] and not
  // java.util.Hashtable[String, String]
  private def getSystemProperties(): mutable.HashMap[String,String] = {
    val map = new mutable.HashMap[String, String]
    for (entry <- JavaConversions.asMap(System.getProperties()).elements) {
      entry match {
        case (k: String, v: String) => map.put(k, v)
        case _ =>
      }
    }
    map
  }

  def getName() = ""

  def getString(key: String): Option[String] = {
    getSystemProperties().get(key).orElse(env.get(key))
  }

  def getConfigMap(key: String): Option[ConfigMap] = None
  def configMap(key: String): ConfigMap = error("not implemented")

  def getList(key: String): Seq[String] = getString(key) match {
    case None => Array[String]()
    case Some(x) => Array[String](x)
  }

  def setString(key: String, value: String): Unit = error("read-only attributes")
  def setList(key: String, value: Seq[String]): Unit = error("read-only attributes")
  def setConfigMap(key: String, value: ConfigMap): Unit = error("read-only attributes")

  def contains(key: String): Boolean = {
    env.contains(key) || getSystemProperties().contains(key)
  }

  def remove(key: String): Boolean = error("read-only attributes")
  def keys: Iterator[String] = (getSystemProperties().keySet ++ env.keySet).elements
  def asMap(): Map[String, String] = error("not implemented")
  def toConfigString = error("not implemented")
  def subscribe(subscriber: Subscriber): SubscriptionKey = error("not implemented")
  def copy(): ConfigMap = this
  def copyInto[T <: ConfigMap](m: T) = m
  def inheritFrom: Option[ConfigMap] = None
  def inheritFrom_=(config: Option[ConfigMap]) = error("not implemented")


  try {
    val addr = InetAddress.getLocalHost
    val ip = addr.getHostAddress
    val dns = addr.getHostName

    if (ip ne null) {
      env("HOSTIP") = ip
    }
    if (dns ne null) {
      env("HOSTNAME") = dns
    }
  } catch {
    case _ => // pass
  }
}
