 /*---------------------------------------------------------------------------\
| Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se> |
\---------------------------------------------------------------------------*/

import sbt._
import sbt.CompileOrder._

import java.util.jar.Attributes
import java.util.jar.Attributes.Name._
import java.io.File

class AkkaParent(info: ProjectInfo) extends DefaultProject(info) {

  // ------------------------------------------------------------
  // project versions
  val JERSEY_VERSION = "1.1.5"
  val ATMO_VERSION = "0.5.4"
  val CASSANDRA_VERSION = "0.5.0"
  val LIFT_VERSION = "2.0-scala280-SNAPSHOT"
  val SCALATEST_VERSION = "1.0.1-for-scala-2.8.0.Beta1-with-test-interfaces-0.3-SNAPSHOT"

  // ------------------------------------------------------------
  lazy val deployPath = info.projectPath / "deploy"
  lazy val distPath = info.projectPath / "dist"

  override def compileOptions = super.compileOptions ++
    Seq("-deprecation", "-Xmigration", "-Xcheckinit", 
        "-Xstrict-warnings", "-Xwarninit", "-encoding", "utf8")
        .map(x => CompileOption(x))

  override def javaCompileOptions = JavaCompileOption("-Xlint:unchecked") :: super.javaCompileOptions.toList

  def distName = "%s_%s-%s.zip".format(name, buildScalaVersion, version)

  lazy val dist = zipTask(allArtifacts, "dist", distName) dependsOn (`package`) describedAs("Zips up the distribution.")

  // ------------------------------------------------------------
  // repositories
  val embeddedrepo = "embedded repo" at (info.projectPath / "embedded-repo").asURL.toString
  val sunjdmk = "sunjdmk" at "http://wp5.e-taxonomy.eu/cdmlib/mavenrepo"
  val databinder = "DataBinder" at "http://databinder.net/repo"
  // val configgy = "Configgy" at "http://www.lag.net/repo"
  val codehaus = "Codehaus" at "http://repository.codehaus.org"
  val codehaus_snapshots = "Codehaus Snapshots" at "http://snapshots.repository.codehaus.org"
  val jboss = "jBoss" at "http://repository.jboss.org/maven2"
  val guiceyfruit = "GuiceyFruit" at "http://guiceyfruit.googlecode.com/svn/repo/releases/"
  val google = "Google" at "http://google-maven-repository.googlecode.com/svn/repository"
  val java_net = "java.net" at "http://download.java.net/maven/2"
  val scala_tools_snapshots = "scala-tools snapshots" at "http://scala-tools.org/repo-snapshots"
  val scala_tools_releases = "scala-tools releases" at "http://scala-tools.org/repo-releases"

  // ------------------------------------------------------------
  // project defintions
  lazy val akka_java_util = project("akka-util-java", "akka-util-java", new AkkaJavaUtilProject(_))
  lazy val akka_util = project("akka-util", "akka-util", new AkkaUtilProject(_))
  lazy val akka_core = project("akka-core", "akka-core", new AkkaCoreProject(_), akka_util, akka_java_util)
  lazy val akka_amqp = project("akka-amqp", "akka-amqp", new AkkaAMQPProject(_), akka_core)
  lazy val akka_http = project("akka-http", "akka-http", new AkkaHttpProject(_), akka_core, akka_camel)
  lazy val akka_camel = project("akka-camel", "akka-camel", new AkkaCamelProject(_), akka_core)
  lazy val akka_persistence = project("akka-persistence", "akka-persistence", new AkkaPersistenceParentProject(_))
  lazy val akka_cluster = project("akka-cluster", "akka-cluster", new AkkaClusterProject(_), akka_core)
  lazy val akka_spring = project("akka-spring", "akka-spring", new AkkaSpringProject(_), akka_core)
  lazy val akka_jta = project("akka-jta", "akka-jta", new AkkaJTAProject(_), akka_core)
  lazy val akka_kernel = project("akka-kernel", "akka-kernel", new AkkaKernelProject(_),
    akka_core, akka_http, akka_spring, akka_camel, akka_persistence,
    akka_cluster, akka_amqp)

  // functional tests in java
  lazy val akka_fun_test = project("akka-fun-test-java", "akka-fun-test-java", new AkkaFunTestProject(_), akka_kernel)

  // examples
  lazy val akka_samples = project("akka-samples", "akka-samples", new AkkaSamplesParentProject(_))

  // ------------------------------------------------------------
  // create executable jar
  override def mainClass = Some("se.scalablesolutions.akka.kernel.Main")

  override def packageOptions =
    manifestClassPath.map(cp => ManifestAttributes(
      (Attributes.Name.CLASS_PATH, cp),
      (IMPLEMENTATION_TITLE, "Akka"),
      (IMPLEMENTATION_URL, "http://akkasource.org"),
      (IMPLEMENTATION_VENDOR, "The Akka Project")
    )).toList :::
    getMainClass(false).map(MainClass(_)).toList

  // create a manifest with all akka jars and dependency jars on classpath
  override def manifestClassPath = Some(allArtifacts.getFiles
    .filter(_.getName.endsWith(".jar"))
    .map("lib_managed/scala_%s/compile/".format(buildScalaVersion) + _.getName)
    .mkString(" ") +
    " scala-library.jar" +
    " dist/akka-util_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-util-java_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-core_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-cluster%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-http_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-camel_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-amqp_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-persistence-common_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-persistence-redis_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-persistence-mongo_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-persistence-cassandra_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-kernel_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-spring_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-jta_%s-%s.jar".format(buildScalaVersion, version)
    )

  // ------------------------------------------------------------
  // publishing
  override def managedStyle = ManagedStyle.Maven
  //override def defaultPublishRepository = Some(Resolver.file("maven-local", Path.userHome / ".m2" / "repository" asFile))
  val publishTo = Resolver.file("maven-local", Path.userHome / ".m2" / "repository" asFile)

  val sourceArtifact = Artifact(artifactID, "source", "jar", Some("source"), Nil, None)
  val docsArtifact = Artifact(artifactID, "docs", "jar", Some("docs"), Nil, None)

  // Credentials(Path.userHome / ".akka_publish_credentials", log)

  //override def documentOptions = encodingUtf8.map(SimpleDocOption(_))
  override def packageDocsJar = defaultJarPath("-docs.jar")
  override def packageSrcJar= defaultJarPath("-source.jar")
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
  class AkkaCoreProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val netty = "org.jboss.netty" % "netty" % "3.2.0.BETA1" % "compile"
    val commons_io = "commons-io" % "commons-io" % "1.4" % "compile"
    val dispatch_json = "net.databinder" % "dispatch-json_2.8.0.Beta1" % "0.6.6" % "compile"
    val dispatch_http = "net.databinder" % "dispatch-http_2.8.0.Beta1" % "0.6.6" % "compile"
    val sjson = "sjson.json" % "sjson" % "0.5-SNAPSHOT-2.8.Beta1" % "compile"
    val sbinary = "sbinary" % "sbinary" % "2.8.0.Beta1-2.8.0.Beta1-0.3.1-SNAPSHOT" % "compile"
    val jackson = "org.codehaus.jackson" % "jackson-mapper-asl" % "1.2.1" % "compile"
    val jackson_core = "org.codehaus.jackson" % "jackson-core-asl" % "1.2.1" % "compile"
    val h2_lzf = "voldemort.store.compress" % "h2-lzf" % "1.0" % "compile"
    val jsr166x = "jsr166x" % "jsr166x" % "1.0" % "compile"
    val jta_1_1 = "org.apache.geronimo.specs" % "geronimo-jta_1.1_spec" % "1.1.1" % "compile"
    // testing
    val scalatest = "org.scalatest" % "scalatest" % SCALATEST_VERSION % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

  class AkkaUtilProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val werkz = "org.codehaus.aspectwerkz" % "aspectwerkz-nodeps-jdk5" % "2.1" % "compile"
    val werkz_core = "org.codehaus.aspectwerkz" % "aspectwerkz-jdk5" % "2.1" % "compile"
    val configgy = "net.lag" % "configgy" % "2.8.0.Beta1-1.5-SNAPSHOT" % "compile"
  }

  class AkkaJavaUtilProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val guicey = "org.guiceyfruit" % "guice-core" % "2.0-beta-4" % "compile"
    val protobuf = "com.google.protobuf" % "protobuf-java" % "2.2.0" % "compile"
    val multiverse = "org.multiverse" % "multiverse-alpha" % "0.5" % "compile"
  }

  class AkkaAMQPProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val commons_io = "commons-io" % "commons-io" % "1.4" % "compile"
    val rabbit = "com.rabbitmq" % "amqp-client" % "1.7.2" % "compile"
  }

  class AkkaHttpProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val jackson_core_asl = "org.codehaus.jackson" % "jackson-core-asl" % "1.2.1" % "compile"
    val stax_api = "javax.xml.stream" % "stax-api" % "1.0-2" % "compile"
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "compile"
    val jersey = "com.sun.jersey" % "jersey-core" % JERSEY_VERSION % "compile"
    val jersey_server = "com.sun.jersey" % "jersey-server" % JERSEY_VERSION % "compile"
    val jersey_json = "com.sun.jersey" % "jersey-json" % JERSEY_VERSION % "compile"
    val jersey_contrib = "com.sun.jersey.contribs" % "jersey-scala" % JERSEY_VERSION % "compile"
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"
    val grizzly = "com.sun.grizzly" % "grizzly-comet-webserver" % "1.9.18-i" % "compile"
    val atmo = "org.atmosphere" % "atmosphere-annotations" % ATMO_VERSION % "compile"
    val atmo_jersey = "org.atmosphere" % "atmosphere-jersey" % ATMO_VERSION % "compile"
    val atmo_runtime = "org.atmosphere" % "atmosphere-runtime" % ATMO_VERSION % "compile"
    val commons_logging = "commons-logging" % "commons-logging" % "1.1.1" % "compile"
    val annotation = "javax.annotation" % "jsr250-api" % "1.0" % "compile"
    val lift_common = "net.liftweb" % "lift-common" % LIFT_VERSION % "compile"
    val lift_util = "net.liftweb" % "lift-util" % LIFT_VERSION % "compile"
    
    // testing
    val scalatest = "org.scalatest" % "scalatest" % SCALATEST_VERSION % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
    val mockito = "org.mockito" % "mockito-all" % "1.8.1" % "test"
  }

  class AkkaCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val camel_core = "org.apache.camel" % "camel-core" % "2.2.0" % "compile"
  }

  class AkkaPersistenceCommonProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val thrift = "com.facebook" % "thrift" % "1.0" % "compile"
    val commons_pool = "commons-pool" % "commons-pool" % "1.5.4" % "compile"
  }

  class AkkaRedisProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val redis = "com.redis" % "redisclient" % "2.8.0.Beta1-1.3-SNAPSHOT" % "compile"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
  }

  class AkkaMongoProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val mongo = "org.mongodb" % "mongo-java-driver" % "1.1" % "compile"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
  }

  class AkkaCassandraProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val cassandra = "org.apache.cassandra" % "cassandra" % CASSANDRA_VERSION % "compile"
    val slf4j = "org.slf4j" % "slf4j-api" % "1.5.8" % "compile"
    val slf4j_log4j = "org.slf4j" % "slf4j-log4j12" % "1.5.8" % "compile"
    val log4j = "log4j" % "log4j" % "1.2.15" % "compile"
    // testing
    val high_scale = "org.apache.cassandra" % "high-scale-lib" % CASSANDRA_VERSION % "test"
    val cassandra_clhm = "org.apache.cassandra" % "clhm-production" % CASSANDRA_VERSION % "test"
    val commons_coll = "commons-collections" % "commons-collections" % "3.2.1" % "test"
    val google_coll = "com.google.collections" % "google-collections" % "1.0" % "test"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
  }

  class AkkaPersistenceParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_persistence_common = project("akka-persistence-common", "akka-persistence-common",
      new AkkaPersistenceCommonProject(_), akka_core)
    lazy val akka_persistence_redis = project("akka-persistence-redis", "akka-persistence-redis",
      new AkkaRedisProject(_), akka_persistence_common)
    lazy val akka_persistence_mongo = project("akka-persistence-mongo", "akka-persistence-mongo",
      new AkkaMongoProject(_), akka_persistence_common)
    lazy val akka_persistence_cassandra = project("akka-persistence-cassandra", "akka-persistence-cassandra",
      new AkkaCassandraProject(_), akka_persistence_common)
  }

  class AkkaClusterProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val jgroups = "jgroups" % "jgroups" % "2.8.0.CR7" % "compile"
  }

  class AkkaKernelProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath)

  class AkkaSpringProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val spring_beans = "org.springframework" % "spring-beans" % "3.0.1.RELEASE" % "compile"
    val spring_context = "org.springframework" % "spring-context" % "3.0.1.RELEASE" % "compile"

    // testing
    val scalatest = "org.scalatest" % "scalatest" % SCALATEST_VERSION % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

  class AkkaJTAProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val atomikos_transactions = "com.atomikos" % "transactions" % "3.2.3" % "compile"
    val atomikos_transactions_jta = "com.atomikos" % "transactions-jta" % "3.2.3" % "compile"
    val atomikos_transactions_api = "com.atomikos" % "transactions-api" % "3.2.3" % "compile"
    //val atomikos_transactions_util = "com.atomikos" % "transactions-util" % "3.2.3" % "compile"
    val jta_spec = "org.apache.geronimo.specs" % "geronimo-jta_1.1_spec" % "1.1.1" % "compile"
  }

  // examples
  class AkkaFunTestProject(info: ProjectInfo) extends DefaultProject(info) {
    val jackson_core_asl = "org.codehaus.jackson" % "jackson-core-asl" % "1.2.1" % "compile"
    val stax_api = "javax.xml.stream" % "stax-api" % "1.0-2" % "compile"
    val protobuf = "com.google.protobuf" % "protobuf-java" % "2.2.0" % "compile"
    val grizzly = "com.sun.grizzly" % "grizzly-comet-webserver" % "1.9.18-i" % "compile"
    val jersey_server = "com.sun.jersey" % "jersey-server" % JERSEY_VERSION % "compile"
    val jersey_json = "com.sun.jersey" % "jersey-json" % JERSEY_VERSION % "compile"
    val jersey_atom = "com.sun.jersey" % "jersey-atom" % JERSEY_VERSION % "compile"
    // testing
    val junit = "junit" % "junit" % "4.5" % "test"
    val jmock = "org.jmock" % "jmock" % "2.4.0" % "test"
  }

  class AkkaSampleChatProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)
  class AkkaSamplePubSubProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleLiftProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val commons_logging = "commons-logging" % "commons-logging" % "1.1.1" % "compile"
    val lift = "net.liftweb" % "lift-webkit" % LIFT_VERSION % "compile"
    val lift_util = "net.liftweb" % "lift-util" % LIFT_VERSION % "compile"
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "compile"
    // testing
    val jetty = "org.mortbay.jetty" % "jetty" % "6.1.22" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

  class AkkaSampleRestJavaProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleRestScalaProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1.1" % "compile"
  }

  class AkkaSampleCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val commons_codec = "commons-codec" % "commons-codec" % "1.3" % "compile"
    val spring_jms = "org.springframework" % "spring-jms" % "3.0.1.RELEASE" % "compile"
    val camel_jetty = "org.apache.camel" % "camel-jetty" % "2.2.0" % "compile"
    val camel_jms = "org.apache.camel" % "camel-jms" % "2.2.0" % "compile"
    val activemq_core = "org.apache.activemq" % "activemq-core" % "5.3.0" % "compile"
  }

  class AkkaSampleSecurityProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1.1" % "compile"
    val jsr250 = "javax.annotation" % "jsr250-api" % "1.0" % "compile"
    val commons_codec = "commons-codec" % "commons-codec" % "1.3" % "compile"
  }

  class AkkaSamplesParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_sample_chat = project("akka-sample-chat", "akka-sample-chat",
      new AkkaSampleChatProject(_), akka_kernel)
    lazy val akka_sample_pubsub = project("akka-sample-pubsub", "akka-sample-pubsub",
      new AkkaSamplePubSubProject(_), akka_kernel)
    lazy val akka_sample_lift = project("akka-sample-lift", "akka-sample-lift",
      new AkkaSampleLiftProject(_), akka_kernel)
    lazy val akka_sample_rest_java = project("akka-sample-rest-java", "akka-sample-rest-java",
      new AkkaSampleRestJavaProject(_), akka_kernel)
    lazy val akka_sample_rest_scala = project("akka-sample-rest-scala", "akka-sample-rest-scala",
      new AkkaSampleRestScalaProject(_), akka_kernel)
    lazy val akka_sample_camel = project("akka-sample-camel", "akka-sample-camel",
      new AkkaSampleCamelProject(_), akka_kernel)
    lazy val akka_sample_security = project("akka-sample-security", "akka-sample-security",
      new AkkaSampleSecurityProject(_), akka_kernel)
    lazy val akka_sample_remote = project("akka-sample-remote", "akka-sample-remote",
      new AkkaSampleRemoteProject(_), akka_kernel)
  }

  // ------------------------------------------------------------
  // helper functions
  def removeDupEntries(paths: PathFinder) =
   Path.lazyPathFinder {
     val mapped = paths.get map { p => (p.relativePath, p) }
    (Map() ++ mapped).values.toList
  }

  def allArtifacts = {
    Path.fromFile(buildScalaInstance.libraryJar) +++
    (removeDupEntries(runClasspath filter ClasspathUtilities.isArchive) +++
    ((outputPath ##) / defaultJarName) +++
    mainResources +++
    mainDependencies.scalaJars +++
    descendents(info.projectPath, "*.conf") +++
    descendents(info.projectPath / "dist", "*.jar") +++
    descendents(info.projectPath / "deploy", "*.jar") +++
    descendents(path("lib") ##, "*.jar") +++
    descendents(configurationPath(Configurations.Compile) ##, "*.jar"))
    .filter(jar => // remove redundant libs
      !jar.toString.endsWith("stax-api-1.0.1.jar") ||
      !jar.toString.endsWith("scala-library-2.7.7.jar")
    )
  }

  class AkkaDefaultProject(info: ProjectInfo, val deployPath: Path) extends DefaultProject(info) with DeployProject

  trait DeployProject extends DefaultProject {
    // defines where the deployTask copies jars to
    def deployPath: Path

    lazy val dist = distAction
    def distAction = deployTask(jarPath, packageDocsJar, packageSrcJar, deployPath, true, true, true) dependsOn(
      `package`, packageDocs, packageSrc) describedAs("Deploying")
    def deployTask(jar: Path, docs: Path, src: Path, toDir: Path, 
                   genJar: Boolean, genDocs: Boolean, genSource: Boolean) = task {
      gen(jar, toDir, genJar, "Deploying bits") orElse
      gen(docs, toDir, genDocs, "Deploying docs") orElse
      gen(src, toDir, genSource, "Deploying sources")
    }
    private def gen(jar: Path, toDir: Path, flag: Boolean, msg: String): Option[String] =
      if (flag) {
        log.info(msg + " " + jar)
        FileUtilities.copyFile(jar, toDir / jar.name, log)
      } else None
  }
}
