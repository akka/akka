package com.typesafe.sbt.multijvm

private[sbt] object Compat {

  type Process = scala.sys.process.Process
  val Process = scala.sys.process.Process

  val Implicits = sjsonnew.BasicJsonProtocol

  type TestResultValue = sbt.TestResult

}
