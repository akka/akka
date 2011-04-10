Using Akka in a Buildr project
==============================

This is an example on how to use Akka in a project based on Buildr

`<code>`_
require 'buildr/scala'

VERSION_NUMBER = "0.6"
GROUP = "se.scalablesolutions.akka"

repositories.remote << "http://www.ibiblio.org/maven2/"
repositories.remote << "http://www.lag.net/repo"
repositories.remote << "http://multiverse.googlecode.com/svn/maven-repository/releases"

AKKA = group('akka-core', 'akka-comet', 'akka-util','akka-kernel', 'akka-rest', 'akka-util-java',
              'akka-security','akka-persistence-common', 'akka-persistence-redis',
              'akka-amqp',
              :under=> 'se.scalablesolutions.akka',
              :version => '0.6')
ASPECTJ = "org.codehaus.aspectwerkz:aspectwerkz-nodeps-jdk5:jar:2.1"
SBINARY = "sbinary:sbinary:jar:0.3"
COMMONS_IO = "commons-io:commons-io:jar:1.4"
CONFIGGY = "net.lag:configgy:jar:1.4.7"
JACKSON = group('jackson-core-asl', 'jackson-mapper-asl',
                :under=> 'org.codehaus.jackson',
                :version => '1.2.1')
MULTIVERSE = "org.multiverse:multiverse-alpha:jar:jar-with-dependencies:0.3"
NETTY = "org.jboss.netty:netty:jar:3.2.0.ALPHA2"
PROTOBUF = "com.google.protobuf:protobuf-java:jar:2.2.0"
REDIS = "com.redis:redisclient:jar:1.0.1"
SJSON = "sjson.json:sjson:jar:0.3"

Project.local_task "run"

desc "Akka Chat Sample Module"
define "akka-sample-chat" do
  project.version = VERSION_NUMBER
  project.group = GROUP

  compile.with AKKA, CONFIGGY

  p artifact(MULTIVERSE).to_s

  package(:jar)

  task "run" do
    Java.java "scala.tools.nsc.MainGenericRunner",
      :classpath => [ compile.dependencies, compile.target,
        ASPECTJ, COMMONS_IO, JACKSON, NETTY, MULTIVERSE, PROTOBUF, REDIS,
        SBINARY, SJSON],
      :java_args => ["-server"]
  end
end
`<code>`_
