/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import annotation.tailrec
import collection.immutable.HashMap

private[akka] object WildcardTree {
  private val empty = new WildcardTree[Nothing]()
  def apply[T](): WildcardTree[T] = empty.asInstanceOf[WildcardTree[T]]
}
private[akka] case class WildcardTree[T](data: Option[T] = None, children: Map[String, WildcardTree[T]] = HashMap[String, WildcardTree[T]]()) {

  def insert(elems: Iterator[String], d: T): WildcardTree[T] =
    if (!elems.hasNext) {
      copy(data = Some(d))
    } else {
      val e = elems.next()
      copy(children = children.updated(e, children.get(e).getOrElse(WildcardTree()).insert(elems, d)))
    }

  @tailrec final def find(elems: Iterator[String]): WildcardTree[T] =
    if (!elems.hasNext) this
    else {
      (children.get(elems.next()) orElse children.get("*")) match {
        case Some(branch) ⇒ branch.find(elems)
        case None         ⇒ WildcardTree()
      }
    }
}
