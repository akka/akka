/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import java.util.{ List => JList }

import akka.annotation.InternalApi

import scala.collection.immutable
import akka.util.ccompat.JavaConverters._
import scala.language.implicitConversions

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] object Utils {

  private[testkit] object JavaCollectionConversions {

    implicit def listConversion[A](jlist: JList[A]): immutable.Seq[A] =
      immutable.Seq(jlist.asScala.toSeq: _*)

  }

  private[testkit] object JavaFuncConversions {

    import java.util.{ function => jf }

    implicit def javaBiFunToScala[A, B, R](javaFun: jf.BiFunction[A, B, R]): (A, B) => R = { (a: A, b: B) =>
      javaFun.apply(a, b)
    }

    implicit def scalaFun1ToJava[I, Re](f: I => Re): jf.Function[I, Re] = new jf.Function[I, Re] {
      override def apply(t: I): Re = f(t)
    }

    implicit def scalaFunToConsumer[I](f: I => Unit): jf.Consumer[I] = new jf.Consumer[I] {
      override def accept(t: I): Unit = f(t)
    }

    implicit def scalaFun2ToJava[I, M, Re](f: (I, M) => Re): jf.BiFunction[I, M, Re] = new jf.BiFunction[I, M, Re] {
      override def apply(t: I, u: M): Re = f(t, u)
    }

  }
}
