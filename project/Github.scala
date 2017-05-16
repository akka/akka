/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

object GitHub {

  def envTokenOrThrow: String =
    System.getenv("PR_VALIDATOR_GH_TOKEN")
      .ensuring(_ != null, "No PR_VALIDATOR_GH_TOKEN env var provided, unable to reach github!")

  def url(v: String): String = {
    val branch = if (v.endsWith("SNAPSHOT")) "master" else "v" + v
    "http://github.com/akka/akka-http/tree/" + branch
  }
}
