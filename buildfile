require 'buildr/scala'

desc 'The Akka Actor Kernel'
define 'akka' do
  project.version = '0.1'
  project.group = 'com.scalablesolutions.akka' 
  package(:zip).include 'README'
  package(:zip).include 'bin/*', :path=>'bin'
  package(:zip).include 'config/*', :path=>'config'
  package(:zip).include 'kernel/lib/*', :path=>'lib'
  package(:zip).include 'kernel/target/*.jar', :path=>'lib'
  package(:zip).include 'supervisor/target/*.jar', :path=>'lib'
  package(:zip).include 'api-java/target/*.jar', :path=>'lib'
  package(:zip).include 'util-java/target/*.jar', :path=>'lib'
end

