import sbt._

class AkkaUtilProject(info: ProjectInfo) extends DefaultProject(info) {

    val akka_databinder = "DataBinder" at "http://databinder.net/repo"
    val akka_configgy = "Configgy" at "http://www.lag.net/repo"
    val akka_multiverse = "Multiverse" at "http://multiverse.googlecode.com/svn/maven-repository/releases"
    val akka_jBoss = "jBoss" at "http://repository.jboss.org/maven2"

    val aspec = "org.codehaus.aspectwerkz" % "aspectwerkz-nodeps-jdk5" % "2.1" % "compile"
    val apsec_core = "org.codehaus.aspectwerkz" % "aspectwerkz-jdk5" % "2.1" % "compile"
    val configgy = "net.lag" % "configgy" % "1.4.7" % "compile"

    override def packageDocsJar = defaultJarPath("-javadoc.jar")
    override def packageSrcJar= defaultJarPath("-sources.jar")

}
