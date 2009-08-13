/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.management

import javax.management._
import java.lang.management._

/*
object ScalaJMX {

  val mbeanServer = ManagementFactory.getPlatformMBeanServer

  def register(t: AnyRef, i: Class, name: ObjectName) = mbeanServer.registerMBean(new StandardMBean(t, i), name)
  def registerBean(bean: DynamicMBean, name: ObjectName): ObjectInstance = mbeanServer.registerMBean(bean, name)
  def register(t: AnyRef, name: String): ObjectInstance = register(t, beanClass(t), name)

  def info(name: ObjectName): SBean = mbeanServer.getMBeanInfo(name)
  def bean(name: ObjectName): SBeanInfo = convBeanInfo(name, mbeanServer.getMBeanInfo(name))
  def invoke(name: ObjectName, operationName: String, params: Array[Object], signature: Array[String]): Object =
    mbeanServer.invoke(name, operationName, params, signature)
  def call(name: ObjectName, operationName: String): Object = invoke(name, operationName, Array[Object](), Array[String]())

  def get(name: ObjectName, attribute: String) = mbeanServer.getAttribute(name, attribute)
  def set(name: ObjectName, attribute: String, value: Object) = mbeanServer.setAttribute(name, new Attribute(attribute, value))

  implicit def instanceToName(oi: ObjectInstance) = oi.getObjectName()
  implicit def stringToName(name: String) = ObjectName.getInstance(name)
  implicit def convBean(bi: MBeanInfo):SBean = SBean(bi.getClassName(), bi.getDescription(), bi.getAttributes(), bi.getNotifications(), bi.getOperations(), bi.getConstructors())
  implicit def seqToArr(seq: Seq[AnyRef]): Array[Object] = seq.toArray

  def convBeanInfo(name: ObjectName, bi: MBeanInfo):SBeanInfo = new SBeanInfo(name, bi.getClassName(), bi.getDescription(), bi.getAttributes(), bi.getNotifications(), bi.getOperations(), bi.getConstructors())

  implicit def convAttrs(attrs: Array[MBeanAttributeInfo]): Seq[SAttr] =
    for (val a <- attrs) yield a
  implicit def convParams(params: Array[MBeanParameterInfo]): Seq[SParameter] =
    for (val p <- params) yield p
  implicit def convNotes(notes: Array[MBeanNotificationInfo]): Seq[SNotification] =
    for (val p <- notes) yield p
  implicit def convCons(cons: Array[MBeanConstructorInfo]): Seq[SConstructor] =
    for (val p <- cons) yield p
  implicit def convOps(cons: Array[MBeanOperationInfo]): Seq[SOperation] =
    for (val p <- cons) yield p

  implicit def convAttr(attr: MBeanAttributeInfo) = SAttr(attr.getName(), attr.getDescription(), attr.getType(), attr.isIs(), attr.isReadable(), attr.isWritable())
  implicit def convNote(note: MBeanNotificationInfo) = SNotification(note.getName(), note.getDescription(), note.getNotifTypes())
  implicit def convOp(op: MBeanOperationInfo):SOperation = SOperation(op.getName(), op.getDescription(), op.getImpact(), op.getReturnType(), op.getSignature())
  implicit def convCon(con: MBeanConstructorInfo):SConstructor = SConstructor(con getName, con getDescription, con getSignature)
  implicit def convParam(p: MBeanParameterInfo) = SParameter(p getName, p getDescription, p getType)

  private def beanClass(t: AnyRef) = Class.forName(t.getClass().getName() + "MBean")
}

class MBean(mbeanInterface: String) extends StandardMBean(Class.forName(mbeanInterface))

abstract class SFeature(val name: String, val description: String)

case class SBean(className: String, description: String,
                 attrs: Seq[SAttr], notes: Seq[SNotification],
                 ops: Seq[SOperation], cons: Seq[SConstructor]) {
  def writable = attrs.toList.filter(sa => sa.writable)
}

class SBeanInfo(name: ObjectName, className: String, description: String,
                attrs: Seq[SAttr], notes: Seq[SNotification],
                ops: Seq[SOperation], cons: Seq[SConstructor])
extends SBean(className, description, attrs, notes, ops, cons) {

  def get(attribute: String) = SJMX.get(name, attribute)
  def set(attribute: String, value: Object) = SJMX.set(name, attribute, value)
  def call(opName: String) = SJMX.call(name, opName)
}

case class SAttr(
  override val name: String,
  override val description: String,
  jmxType: String, isIs: boolean, readable: boolean, writable: boolean
) extends SFeature(name, description)

case class SNotification(
  override val name: String,
  override val description: String,
  notifTypes: Array[String]) extends SFeature(name, description)

case class SOperation(
  override val name: String,
  override val description: String,
  impact: int,
  returnType: String,
  signature: Seq[SParameter]) extends SFeature(name, description)

case class SParameter(
  override val name: String,
  override val description: String,
  jmxType: String) extends SFeature(name, description)

case class SConstructor(
  override val name: String,
  override val description: String,
  signature: Seq[SParameter]) extends SFeature(name, description)

*/

/*
package com.soletta.spipe;
 
import javax.management.{StandardMBean,ObjectName,MBeanInfo};
 
class SPipe extends MBean("com.soletta.spipe.SPipeMBean") with SPipeMBean {
    
  import Console.println;
  import SJMX._;
  
  private var desc: String = "Yipe!";
  
  def go = {
      val oname: ObjectName = "default:name=SPipe";
      val instance = SJMX.registerBean(this, oname);
      
      set(oname, "Factor", "Hello!");
      println(get(oname, "Factor"));
      
      val SBean(n, d, Seq(_, a2, a3, _*), _, ops, _) = info(oname);
      println("Bean name is " + n + ", description is " + d);
      println("Second attribute is " + a2);
      println("Third attribute is " + a3);
      println("Writable attributes are " + info(oname).writable);
      println("Ops: " + ops);
      
      val x = 
          <bean name={n} description={d}>
            {ops.toList.map(o => <operation name={o.name} description={o.description}/>)}
          </bean> ;
          
      println(x);
      
      val inf = bean(oname);
      inf.call("start");
      println(inf.get("Factor"));
      
  }
  
  def getName = "SPipe!";
  def setDescription(d: String) = desc = d;
  override def getDescription() = desc;
  def getFactor = desc;
  def setFactor(s: String) = desc = s;
	def isHappy = true;
  
  override def getDescription(info: MBeanInfo) = desc;
  
}
 
object PipeMain {
    def main(args: Array[String]): unit = {
        (new SPipe) go;
    }
}

trait SPipeMBean {
  def getName: String;
  def getDescription: String = getName;
  def setDescription(d: String): unit;
  def getFactor: String;
  def setFactor(s: String): unit;
  def isHappy: boolean;
  
  def start() = { Console.println("Starting"); }
  def stop() = { }
*/
