package akka

object GitHub {
  def url(v: String): String = {
    val branch = if (v.endsWith("SNAPSHOT")) "master" else "v" + v
    "http://github.com/akka/akka/tree/" + branch
  }
}
