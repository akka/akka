import sbt._

class AkkaParent(info: ProjectInfo) extends ParentProject(info) {
    //repos
    val sunjdmk = "sunjdmk" at "http://wp5.e-taxonomy.eu/cdmlib/mavenrepo"
    val databinder = "DataBinder" at "http://databinder.net/repo"
    val configgy = "Configgy" at "http://www.lag.net/repo"
    val multiverse = "Multiverse" at "http://multiverse.googlecode.com/svn/maven-repository/releases"
    val jboss = "jBoss" at "http://repository.jboss.org/maven2"
    val guiceyfruit = "GuiceyFruit" at "http://guiceyfruit.googlecode.com/svn/repo/releases/"
    val embeddedrepo = "embedded repo" at "http://guice-maven.googlecode.com/svn/trunk"
    val google = "google" at "http://google-maven-repository.googlecode.com/svn/repository"
    val m2 = "m2" at "http://download.java.net/maven/2"

    //project versions
    val JERSEYVERSION = "1.1.5"
    val ATMOVERSION = "0.6-SNAPSHOT"
    val CASSANDRAVERSION = "0.5.0"

    //project defintions
    lazy val javautil = project("akka-util-java", "akka-java-util", new AkkaJavaUtilProject(_))
    lazy val util = project("akka-util", "akka-util",new AkkaUtilProject(_))
    lazy val core = project("akka-core", "akka-core", new AkkaCoreProject(_), util, javautil)
    lazy val amqp = project("akka-amqp", "akka-amqp", new AkkaAMQPProject(_), core) 
    lazy val rest = project("akka-rest", "akka-rest", new AkkaRestProject(_), core)
    lazy val comet = project("akka-comet", "akka-comet",new AkkaCometProject(_), rest)
    lazy val patterns = project("akka-patterns", "akka-patterns", new AkkaPatternsProject(_), core)
    lazy val security = project("akka-security", "akka-security", new AkkaSecurityProject(_), core)
    lazy val persitence = project("akka-persistence", "akka-persistence", new AkkaPersistenceParentProject(_))

  // subprojects
  class AkkaCoreProject(info: ProjectInfo) extends DefaultProject(info) {
    val sjson = "sjson.json" % "sjson" % "0.4" % "compile" 
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
    //testing
    val scalatest= "org.scalatest" % "scalatest" % "1.0" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

  class AkkaUtilProject(info: ProjectInfo) extends DefaultProject(info) {
    val aspec = "org.codehaus.aspectwerkz" % "aspectwerkz-nodeps-jdk5" % "2.1" % "compile"
    val apsec_core = "org.codehaus.aspectwerkz" % "aspectwerkz-jdk5" % "2.1" % "compile"
    val configgy = "net.lag" % "configgy" % "1.4.7" % "compile"
  }

  class AkkaJavaUtilProject(info: ProjectInfo) extends DefaultProject(info) {
    val guicey = "org.guiceyfruit" % "guice-core" % "2.0-beta-4" % "compile"
    val proto = "com.google.protobuf" % "protobuf-java" % "2.2.0" % "compile"
    val multi = "org.multiverse" % "multiverse-alpha" % "0.3" % "compile"
  }

  class AkkaAMQPProject(info:ProjectInfo) extends DefaultProject(info) {
    val rabbit = "com.rabbitmq" % "amqp-client" % "1.7.2"
  }

  class AkkaRestProject(info:ProjectInfo) extends DefaultProject(info) {
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "provided"
    val jersey = "com.sun.jersey" % "jersey-core" % JERSEYVERSION % "compile"
    val jerseyserver = "com.sun.jersey" % "jersey-server" % JERSEYVERSION % "compile"
    val jerseyjson = "com.sun.jersey" % "jersey-json" % JERSEYVERSION % "compile"
    val jerseycontrib = "com.sun.jersey.contribs" % "jersey-scala" % JERSEYVERSION % "compile"
    val jsr = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"
  }

  class AkkaCometProject(info:ProjectInfo) extends DefaultProject(info) {
    val grizzly = "com.sun.grizzly" % "grizzly-comet-webserver" % "1.9.18-i" % "compile"
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "provided"
    val atmo = "org.atmosphere" % "atmosphere-annotations" % ATMOVERSION % "compile"
    val atmojersey = "org.atmosphere" % "atmosphere-jersey" % ATMOVERSION % "compile"
    val atmoruntime = "org.atmosphere" % "atmosphere-runtime" % ATMOVERSION % "compile"
  }

  class AkkaPatternsProject(info:ProjectInfo) extends DefaultProject(info) {
    //testing
    val scalatest= "org.scalatest" % "scalatest" % "1.0" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

  class AkkaSecurityProject(info:ProjectInfo) extends DefaultProject(info) {
    val annotation = "javax.annotation" % "jsr250-api" % "1.0"
    val jerseyserver = "com.sun.jersey" % "jersey-server" % JERSEYVERSION % "compile"
    val jsr = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"
    val lift = "net.liftweb" % "lift-util" % "1.1-M6" % "compile"
    //testing
    val scalatest= "org.scalatest" % "scalatest" % "1.0" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
    val mockito = "org.mockito" % "mockito-all" % "1.8.1" % "test"
  }


  class AkkaPersistenceCommonProject(info:ProjectInfo) extends DefaultProject(info) {
   val facebook = "com.facebook" % "thrift" % "1.0" % "compile"
   val commonspool = "commons-pool" % "commons-pool" % "1.5.1" % "compile"
  }
  class AkkaRedisProject(info:ProjectInfo) extends DefaultProject(info) {
    val redis = "com.redis" % "redisclient" % "1.0.1" % "compile"
  }

  class AkkaMongoProject(info:ProjectInfo) extends DefaultProject(info) {
    val mongo = "org.mongodb" % "mongo-java-driver" % "1.1" % "compile"
  }

  class AkkaCassandraProject(info:ProjectInfo) extends DefaultProject(info) {
    val cassandra = "org.apache.cassandra" % "cassandra" % CASSANDRAVERSION % "compile"
    val cassandralib = "org.apache.cassandra" % "high-scale-lib" % CASSANDRAVERSION % "test"
    val cassandraclhm = "org.apache.cassandra" % "clhm-production" % CASSANDRAVERSION % "test"
    val commonscoll = "commons-collections" % "commons-collections" % "3.2.1" % "test"
    val googlecoll = "com.google.collections" % "google-collections" % "1.0" % "test"
    val slf4j = "org.slf4j" % "slf4j-api" % "1.5.8" % "test"
    val slf4jlog4j = "org.slf4j" % "slf4j-log4j12" % "1.5.8" % "test"
    val log4j = "log4j" % "log4j" % "1.2.15" % "test"
  }
  
  class AkkaPersistenceParentProject(info:ProjectInfo) extends ParentProject(info) {
     lazy val akkapersistencecommon = project ("akka-persistence-common", "akka-persistence-common", new AkkaPersistenceCommonProject(_))
     lazy val redis = project("akka-persistence-redis","akka-persistence-redis", new AkkaRedisProject(_),akkapersistencecommon)
     lazy val mongo = project("akka-persistence-mongo","akka-persistence-mongo", new AkkaMongoProject(_),akkapersistencecommon)
     lazy val cassandra = project("akka-persistence-cassandra","akka-persistence-cassandra", new AkkaCassandraProject(_),akkapersistencecommon)

  }
}
