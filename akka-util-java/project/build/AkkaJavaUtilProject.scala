import sbt._

class AkkaJavaUtilProject(info: ProjectInfo) extends DefaultProject(info) {

    val akka_databinder = "DataBinder" at "http://databinder.net/repo"
    val akka_configgy = "Configgy" at "http://www.lag.net/repo"
    val akka_multiverse = "Multiverse" at "http://multiverse.googlecode.com/svn/maven-repository/releases"
    val akka_jBoss = "jBoss" at "http://repository.jboss.org/maven2"
 val guiceyfruit = "GuiceyFruit" at "http://guiceyfruit.googlecode.com/svn/repo/releases/"

    val guicey = "org.guiceyfruit" % "guice-core" % "2.0-beta-4" % "compile"
    val proto = "com.google.protobuf" % "protobuf-java" % "2.2.0" % "compile"
    val multi = "org.multiverse" % "multiverse-alpha" % "0.3" % "compile"

    override def packageDocsJar = defaultJarPath("-javadoc.jar")
    override def packageSrcJar= defaultJarPath("-sources.jar")

}
