/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

package object serialization {
  type JsValue = _root_.dispatch.json.JsValue
  val JsValue = _root_.dispatch.json.JsValue
  val Js = _root_.dispatch.json.Js
  val JsonSerialization = sjson.json.JsonSerialization
  val DefaultProtocol = sjson.json.DefaultProtocol
}
