/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

@deprecated("will be removed to sanitize dependencies, use sjson directly", "2.0.1")
package object serialization {
  type JsValue = _root_.dispatch.json.JsValue
  val JsValue = _root_.dispatch.json.JsValue
  val Js = _root_.dispatch.json.Js
  val JsonSerialization = sjson.json.JsonSerialization
  val DefaultProtocol = sjson.json.DefaultProtocol
}
