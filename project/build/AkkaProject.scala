/*-------------------------------------------------------------------------------
   Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>

   ----------------------------------------------------
   -------- sbt buildfile for the Akka project --------
   ----------------------------------------------------

   Akka implements a unique hybrid of:
    * Actors , which gives you:
    * Simple and high-level abstractions for concurrency and parallelism.
    * Asynchronous, non-blocking and highly performant event-driven programming 
      model.
    * Very lightweight event-driven processes (create ~6.5 million actors on 
      4 G RAM).
    * Supervision hierarchies with let-it-crash semantics. For writing highly 
      fault-tolerant systems that never stop, systems that self-heal.
    * Software Transactional Memory (STM). (Distributed transactions coming soon).
    * Transactors: combine actors and STM into transactional actors. Allows you to 
      compose atomic message flows with automatic rollback and retry.
    * Remoting: highly performant distributed actors with remote supervision and 
      error management.
    * Cluster membership management.

  Akka also has a set of add-on modules:
    * Persistence: A set of pluggable back-end storage modules that works in sync 
      with the STM.
    * Cassandra distributed and highly scalable database.
    * MongoDB document database.
    * Redis data structures database (upcoming)
    * REST (JAX-RS): Expose actors as REST services.
    * Comet: Expose actors as Comet services.
    * Security: Digest and Kerberos based security.
    * Microkernel: Run Akka as a stand-alone kernel.
    
-------------------------------------------------------------------------------*/

import sbt._
import java.util.jar.Attributes

class AkkaParent(info: ProjectInfo) extends DefaultProject(info) {

  // ------------------------------------------------------------
  // project versions
  val JERSEY_VERSION = "1.1.5"
  val ATMO_VERSION = "0.5.4"
  val CASSANDRA_VERSION = "0.5.0"

  // ------------------------------------------------------------
  lazy val akkaHome = {
    val home = System.getenv("AKKA_HOME")
    if (home == null) throw new Error("You need to set the $AKKA_HOME environment variable to the root of the Akka distribution")
    home
  }
  lazy val deployPath = Path.fromFile(new java.io.File(akkaHome + "/deploy"))
  lazy val distPath = Path.fromFile(new java.io.File(akkaHome + "/dist"))

  lazy val dist = zipTask(allArtifacts, "dist", distName) dependsOn (`package`) describedAs("Zips up the distribution.")

  def distName = "%s_%s-%s.zip".format(name, defScalaVersion.value, version)
  
  // ------------------------------------------------------------
  // repositories
  val sunjdmk = "sunjdmk" at "http://wp5.e-taxonomy.eu/cdmlib/mavenrepo"
  val databinder = "DataBinder" at "http://databinder.net/repo"
  val configgy = "Configgy" at "http://www.lag.net/repo"
  val codehaus = "Codehaus" at "http://repository.codehaus.org"
  val codehaus_snapshots = "Codehaus Snapshots" at "http://snapshots.repository.codehaus.org"
  val jboss = "jBoss" at "http://repository.jboss.org/maven2"
  val guiceyfruit = "GuiceyFruit" at "http://guiceyfruit.googlecode.com/svn/repo/releases/"
  val embeddedrepo = "embedded repo" at "http://guice-maven.googlecode.com/svn/trunk"
  val google = "google" at "http://google-maven-repository.googlecode.com/svn/repository"
  val m2 = "m2" at "http://download.java.net/maven/2"

  // ------------------------------------------------------------
  // project defintions
  lazy val akka_java_util = project("akka-util-java", "akka-util-java", new AkkaJavaUtilProject(_))
  lazy val akka_util = project("akka-util", "akka-util", new AkkaUtilProject(_))
  lazy val akka_core = project("akka-core", "akka-core", new AkkaCoreProject(_), akka_util, akka_java_util)
  lazy val akka_amqp = project("akka-amqp", "akka-amqp", new AkkaAMQPProject(_), akka_core)
  lazy val akka_rest = project("akka-rest", "akka-rest", new AkkaRestProject(_), akka_core)
  lazy val akka_comet = project("akka-comet", "akka-comet", new AkkaCometProject(_), akka_rest)
  lazy val akka_camel = project("akka-camel", "akka-camel", new AkkaCamelProject(_), akka_core)
  lazy val akka_patterns = project("akka-patterns", "akka-patterns", new AkkaPatternsProject(_), akka_core)
  lazy val akka_security = project("akka-security", "akka-security", new AkkaSecurityProject(_), akka_core)
  lazy val akka_persistence = project("akka-persistence", "akka-persistence", new AkkaPersistenceParentProject(_))
  lazy val akka_cluster = project("akka-cluster", "akka-cluster", new AkkaClusterParentProject(_))
  lazy val akka_kernel = project("akka-kernel", "akka-kernel", new AkkaKernelProject(_), 
    akka_core, akka_rest, akka_persistence, akka_cluster, akka_amqp, akka_security, akka_comet, akka_camel, akka_patterns)

  // functional tests in java
  lazy val akka_fun_test = project("akka-fun-test-java", "akka-fun-test-java", new AkkaFunTestProject(_), akka_kernel)

  // examples
  lazy val akka_samples = project("akka-samples", "akka-samples", new AkkaSamplesParentProject(_))

  // ------------------------------------------------------------
  // create executable jar
  override def mainClass = Some("se.scalablesolutions.akka.kernel.Main")

  override def packageOptions = 
    manifestClassPath.map(cp => ManifestAttributes((Attributes.Name.CLASS_PATH, cp))).toList :::
    getMainClass(false).map(MainClass(_)).toList

  // create a manifest with all akka jars and dependency jars on classpath
  override def manifestClassPath = Some(allArtifacts.getFiles
    .filter(_.getName.endsWith(".jar"))
    .map("lib_managed/scala_%s/compile/".format(defScalaVersion.value) + _.getName)
    .mkString(" ") + 
    " dist/akka-util_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-util-java_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-core_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-cluster-shoal_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-cluster-jgroups_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-rest_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-comet_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-camel_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-security_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-amqp_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-patterns_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-persistence-common_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-persistence-redis_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-persistence-mongo_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-persistence-cassandra_%s-%s.jar".format(defScalaVersion.value, version) +
    " dist/akka-kernel_%s-%s.jar".format(defScalaVersion.value, version)
    ) 

  // ------------------------------------------------------------
  // publishing
  Credentials(Path.userHome / ".akka_publish_credentials", log)
  override def managedStyle = ManagedStyle.Maven

  val publishTo = "Scalable Solutions Maven Repository" at "http://scalablesolutions.se/akka/repository/"
  val sourceArtifact = Artifact(artifactID, "src", "jar", Some("sources"), Nil, None)
  val docsArtifact = Artifact(artifactID, "docs", "jar", Some("javadoc"), Nil, None)

  override def packageDocsJar = defaultJarPath("-javadoc.jar")
  override def packageSrcJar= defaultJarPath("-sources.jar")
  override def packageToPublishActions = super.packageToPublishActions ++ Seq(packageDocs, packageSrc)
  
  override def pomExtra =
    <inceptionYear>2009</inceptionYear>
    <url>http://akkasource.org</url>
    <organization>
      <name>Scalable Solutions AB</name>
      <url>http://scalablesolutions.se</url>
    </organization>
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        <distribution>repo</distribution>
      </license>
    </licenses>

    // ------------------------------------------------------------
  // subprojects
  class AkkaCoreProject(info: ProjectInfo) extends DefaultProject(info) {
    val netty = "org.jboss.netty" % "netty" % "3.2.0.BETA1" % "compile"
    val commons_io = "commons-io" % "commons-io" % "1.4" % "compile"
    val dispatch_json = "net.databinder" % "dispatch-json_2.7.7" % "0.6.4" % "compile"
    val dispatch_htdisttp = "net.databinder" % "dispatch-http_2.7.7" % "0.6.4" % "compile"
    val sjson = "sjson.json" % "sjson" % "0.4" % "compile"
    val sbinary = "sbinary" % "sbinary" % "0.3" % "compile"
    val jackson = "org.codehaus.jackson" % "jackson-mapper-asl" % "1.2.1" % "compile"
    val jackson_core = "org.codehaus.jackson" % "jackson-core-asl" % "1.2.1" % "compile"
    val voldemort = "voldemort.store.compress" % "h2-lzf" % "1.0" % "compile"
    val javautils = "org.scala-tools" % "javautils" % "2.7.4-0.1" % "compile"
    // testing
    val scalatest = "org.scalatest" % "scalatest" % "1.0" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaUtilProject(info: ProjectInfo) extends DefaultProject(info) {
    val werkz = "org.codehaus.aspectwerkz" % "aspectwerkz-nodeps-jdk5" % "2.1" % "compile"
    val werkz_core = "org.codehaus.aspectwerkz" % "aspectwerkz-jdk5" % "2.1" % "compile"
    val configgy = "net.lag" % "configgy" % "1.4.7" % "compile"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaJavaUtilProject(info: ProjectInfo) extends DefaultProject(info) {
    val guicey = "org.guiceyfruit" % "guice-core" % "2.0-beta-4" % "compile"
    val protobuf = "com.google.protobuf" % "protobuf-java" % "2.2.0" % "compile"
    val multiverse = "org.multiverse" % "multiverse-alpha" % "0.4-SNAPSHOT" % "compile"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaAMQPProject(info: ProjectInfo) extends DefaultProject(info) {
    val rabbit = "com.rabbitmq" % "amqp-client" % "1.7.2"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaRestProject(info: ProjectInfo) extends DefaultProject(info) {
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "compile"
    val jersey = "com.sun.jersey" % "jersey-core" % JERSEY_VERSION % "compile"
    val jersey_server = "com.sun.jersey" % "jersey-server" % JERSEY_VERSION % "compile"
    val jersey_json = "com.sun.jersey" % "jersey-json" % JERSEY_VERSION % "compile"
    val jersey_contrib = "com.sun.jersey.contribs" % "jersey-scala" % JERSEY_VERSION % "compile"
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaCometProject(info: ProjectInfo) extends DefaultProject(info) {
    val grizzly = "com.sun.grizzly" % "grizzly-comet-webserver" % "1.9.18-i" % "compile"
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "compile"
    val atmo = "org.atmosphere" % "atmosphere-annotations" % ATMO_VERSION % "compile"
    val atmo_jersey = "org.atmosphere" % "atmosphere-jersey" % ATMO_VERSION % "compile"
    val atmo_runtime = "org.atmosphere" % "atmosphere-runtime" % ATMO_VERSION % "compile"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaCamelProject(info: ProjectInfo) extends DefaultProject(info) {
    val camel_core = "org.apache.camel" % "camel-core" % "2.2.0" % "compile"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaPatternsProject(info: ProjectInfo) extends DefaultProject(info) {
    // testing
    val scalatest = "org.scalatest" % "scalatest" % "1.0" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaSecurityProject(info: ProjectInfo) extends DefaultProject(info) {
    val annotation = "javax.annotation" % "jsr250-api" % "1.0"
    val jersey_server = "com.sun.jersey" % "jersey-server" % JERSEY_VERSION % "compile"
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"
    val lift_util = "net.liftweb" % "lift-util" % "1.1-M6" % "compile"
    // testing
    val scalatest = "org.scalatest" % "scalatest" % "1.0" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
    val mockito = "org.mockito" % "mockito-all" % "1.8.1" % "test"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaPersistenceCommonProject(info: ProjectInfo) extends DefaultProject(info) {
    val thrift = "com.facebook" % "thrift" % "1.0" % "compile"
    val commons_pool = "commons-pool" % "commons-pool" % "1.5.1" % "compile"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaRedisProject(info: ProjectInfo) extends DefaultProject(info) {
    val redis = "com.redis" % "redisclient" % "1.1" % "compile"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaMongoProject(info: ProjectInfo) extends DefaultProject(info) {
    val mongo = "org.mongodb" % "mongo-java-driver" % "1.1" % "compile"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaCassandraProject(info: ProjectInfo) extends DefaultProject(info) {
    val cassandra = "org.apache.cassandra" % "cassandra" % CASSANDRA_VERSION % "compile"
    val high_scale = "org.apache.cassandra" % "high-scale-lib" % CASSANDRA_VERSION % "test"
    val cassandra_clhm = "org.apache.cassandra" % "clhm-production" % CASSANDRA_VERSION % "test"
    val commons_coll = "commons-collections" % "commons-collections" % "3.2.1" % "test"
    val google_coll = "com.google.collections" % "google-collections" % "1.0" % "test"
    val slf4j = "org.slf4j" % "slf4j-api" % "1.5.8" % "test"
    val slf4j_log4j = "org.slf4j" % "slf4j-log4j12" % "1.5.8" % "test"
    val log4j = "log4j" % "log4j" % "1.2.15" % "test"    
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaPersistenceParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_persistence_common = project("akka-persistence-common", "akka-persistence-common", new AkkaPersistenceCommonProject(_), akka_core)
    lazy val akka_persistence_redis = project("akka-persistence-redis", "akka-persistence-redis", new AkkaRedisProject(_), akka_persistence_common)
    lazy val akka_persistence_mongo = project("akka-persistence-mongo", "akka-persistence-mongo", new AkkaMongoProject(_), akka_persistence_common)
    lazy val akka_persistence_cassandra = project("akka-persistence-cassandra", "akka-persistence-cassandra", new AkkaCassandraProject(_), akka_persistence_common)
  }

  class AkkaJgroupsProject(info: ProjectInfo) extends DefaultProject(info) {
    val jgroups = "jgroups" % "jgroups" % "2.8.0.CR7" % "compile"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaShoalProject(info: ProjectInfo) extends DefaultProject(info) {
    val shoal = "shoal-jxta" % "shoal" % "1.1-20090818" % "compile"
    val shoal_extra = "shoal-jxta" % "jxta" % "1.1-20090818" % "compile"
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaClusterParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_cluster_jgroups = project("akka-cluster-jgroups", "akka-cluster-jgroups", new AkkaJgroupsProject(_), akka_core)
    lazy val akka_cluster_shoal = project("akka-cluster-shoal", "akka-cluster-shoal", new AkkaShoalProject(_), akka_core)
  }

  class AkkaKernelProject(info: ProjectInfo) extends DefaultProject(info) {
    lazy val dist = deployTask(info, distPath) dependsOn(`package`) describedAs("Deploying")
  }

  // examples
  class AkkaFunTestProject(info: ProjectInfo) extends DefaultProject(info) {
    val protobuf = "com.google.protobuf" % "protobuf-java" % "2.2.0"
    val grizzly = "com.sun.grizzly" % "grizzly-comet-webserver" % "1.9.18-i" % "compile"
    val jersey_server = "com.sun.jersey" % "jersey-server" % JERSEY_VERSION % "compile"
    val jersey_json = "com.sun.jersey" % "jersey-json" % JERSEY_VERSION % "compile"
    val jersey_atom = "com.sun.jersey" % "jersey-atom" % JERSEY_VERSION % "compile"
    // testing
    val junit = "junit" % "junit" % "4.5" % "test"
    val jmock = "org.jmock" % "jmock" % "2.4.0" % "test"
  }

  class AkkaSampleChatProject(info: ProjectInfo) extends DefaultProject(info) {
    lazy val dist = deployTask(info, deployPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaSampleLiftProject(info: ProjectInfo) extends DefaultProject(info) {
    val lift = "net.liftweb" % "lift-webkit" % "1.1-M6" % "compile"
    val lift_util = "net.liftweb" % "lift-util" % "1.1-M6" % "compile"
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "compile"
    // testing
    val jetty = "org.mortbay.jetty" % "jetty" % "6.1.11" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
    lazy val dist = deployTask(info, deployPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaSampleRestJavaProject(info: ProjectInfo) extends DefaultProject(info) {
    lazy val dist = deployTask(info, deployPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaSampleRestScalaProject(info: ProjectInfo) extends DefaultProject(info) {
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"
    lazy val dist = deployTask(info, deployPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaSampleCamelProject(info: ProjectInfo) extends DefaultProject(info) {
    val jetty = "org.mortbay.jetty" % "jetty" % "6.1.11" % "compile"
    val jetty_client = "org.mortbay.jetty" % "jetty-client" % "6.1.11" % "compile"
    val camel_http = "org.apache.camel" % "camel-http" % "2.2.0" % "compile"
    val camel_jetty = "org.apache.camel" % "camel-jetty" % "2.2.0" % "compile" intransitive()
    val camel_jms = "org.apache.camel" % "camel-jms" % "2.2.0" % "compile"
    val camel_cometd = "org.apache.camel" % "camel-cometd" % "2.2.0" % "compile"
    val activemq_core = "org.apache.activemq" % "activemq-core" % "5.3.0" % "compile"
    lazy val dist = deployTask(info, deployPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaSampleSecurityProject(info: ProjectInfo) extends DefaultProject(info) {
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"
    val jsr250 = "javax.annotation" % "jsr250-api" % "1.0"
    lazy val dist = deployTask(info, deployPath) dependsOn(`package`) describedAs("Deploying")
  }

  class AkkaSamplesParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_sample_chat = project("akka-sample-chat", "akka-sample-chat", new AkkaSampleChatProject(_), akka_kernel)
    lazy val akka_sample_lift = project("akka-sample-lift", "akka-sample-lift", new AkkaSampleLiftProject(_), akka_kernel)
    lazy val akka_sample_rest_java = project("akka-sample-rest-java", "akka-sample-rest-java", new AkkaSampleRestJavaProject(_), akka_kernel)
    lazy val akka_sample_rest_scala = project("akka-sample-rest-scala", "akka-sample-rest-scala", new AkkaSampleRestScalaProject(_), akka_kernel)
    lazy val akka_sample_camel = project("akka-sample-camel", "akka-sample-camel", new AkkaSampleCamelProject(_), akka_kernel)
    lazy val akka_sample_security = project("akka-sample-security", "akka-sample-security", new AkkaSampleSecurityProject(_), akka_kernel)
  }

  // ------------------------------------------------------------
  // helper functions
  def removeDupEntries(paths: PathFinder) =
   Path.lazyPathFinder {
     val mapped = paths.get map { p => (p.relativePath, p) }
    (Map() ++ mapped).values.toList
  }

  def allArtifacts = {
    (removeDupEntries(runClasspath filter ClasspathUtilities.isArchive) +++
    ((outputPath ##) / defaultJarName) +++
    mainResources +++
    mainDependencies.scalaJars +++
    descendents(info.projectPath, "*.conf") +++
    descendents(info.projectPath / "dist", "*.jar") +++
    descendents(info.projectPath / "deploy", "*.jar") +++
    descendents(path("lib") ##, "*.jar") +++
    descendents(configurationPath(Configurations.Compile) ##, "*.jar"))
    .filter(jar =>
      !jar.toString.endsWith("scala-library-2.7.5.jar") && // remove redundant scala libs
      !jar.toString.endsWith("scala-library-2.7.6.jar")) 
  }

  def deployTask(info: ProjectInfo, toDir: Path) = task {
    val projectPath = info.projectPath.toString
    val moduleName = projectPath.substring(projectPath.lastIndexOf(System.getProperty("file.separator")) + 1, projectPath.length)
    // FIXME need to find out a way to grab these paths from the sbt system 
    val JAR_FILE_NAME = moduleName + "_%s-%s.jar".format(defScalaVersion.value, version)
    val JAR_FILE_PATH = projectPath + "/target/scala_%s/".format(defScalaVersion.value) + JAR_FILE_NAME

    val from = Path.fromFile(new java.io.File(JAR_FILE_PATH))
    val to = Path.fromFile(new java.io.File(toDir + "/" + JAR_FILE_NAME))
    log.info("Deploying " + to)
    FileUtilities.copyFile(from, to, log)
  }
}
