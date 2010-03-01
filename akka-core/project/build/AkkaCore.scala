import sbt._

class AkkaCoreProject(info: ProjectInfo) extends DefaultProject(info) {

    val akkautil =  "se.scalablesolutions.akka" % "akka-util" % "0.7-SNAPSHOT" % "compile"    
    val akkautiljava =  "se.scalablesolutions.akka" % "akka-util-java" % "0.7-SNAPSHOT" % "compile"    

    val akka_databinder = "DataBinder" at "http://databinder.net/repo"
    val akka_multiverse = "Multiverse" at "http://multiverse.googlecode.com/svn/maven-repository/releases"
    val akka_jBoss = "jBoss" at "http://repository.jboss.org/maven2"

    val aspec = "org.codehaus.aspectwerkz" % "aspectwerkz-nodeps-jdk5" % "2.1" % "compile"
    val apsec_core = "org.codehaus.aspectwerkz" % "aspectwerkz-jdk5" % "2.1" % "compile"
    val commonsio = "commons-io" % "commons-io" % "1.4" % "compile"
    val netdatabinder = "net.databinder" % "dispatch-json_2.7.7" % "0.6.4" % "compile"
    val netdatabinderhttp = "net.databinder" % "dispatch-http_2.7.7" % "0.6.4" % "compile"
    val sbinary = "sbinary" % "sbinary" % "0.3" % "compile"
    val jack = "org.codehaus.jackson" % "jackson-mapper-asl" % "1.2.1" % "compile"
    val jackcore = "org.codehaus.jackson" % "jackson-core-asl" % "1.2.1" % "compile"
    val volde = "voldemort.store.compress" % "h2-lzf" % "1.0" % "compile"
    val scalajavautil = "org.scala-tools" % "javautils" % "2.7.4-0.1" % "compile"
    val netty = "org.jboss.netty" % "netty" % "3.2.0.ALPHA3" % "compile"    

    val scalatest= "org.scalatest" % "scalatest" % "1.0" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
    override def packageDocsJar = defaultJarPath("-javadoc.jar")
    override def packageSrcJar= defaultJarPath("-sources.jar")

}
