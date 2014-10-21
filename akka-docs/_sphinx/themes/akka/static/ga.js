// check to see if this document is on the akka.io server. If so, google analytics and marketo
if (/akka\.io/.test(document.domain)) {
  var _gaq = _gaq || [];
  _gaq.push(['_setAccount', 'UA-21117439-1']);
  _gaq.push(['_setDomainName', 'akka.io']);
  _gaq.push(['_trackPageview']);

  (function() {
    var ga = document.createElement('script'); ga.type = 'text/javascript'; ga.async = true;
    ga.src = ('https:' == document.location.protocol ? 'https://ssl' : 'http://www') + '.google-analytics.com/ga.js';
    var s = document.getElementsByTagName('script')[0]; s.parentNode.insertBefore(ga, s);
  })();

  (function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
  (i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
  m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
  })(window,document,'script','//www.google-analytics.com/analytics.js','ga');
  ga('create', 'UA-23127719-1', 'typesafe.com', {'allowLinker': true, 'name': 'tsTracker'});
  ga('tsTracker.require', 'linker');
  ga('tsTracker.linker:autoLink', ['typesafe.com','playframework.com','scala-lang.org','scaladays.org','spray.io','akka.io','scala-sbt.org','scala-ide.org']);
  ga('tsTracker.send', 'pageview');

  (function() {
    var didInit = false;
    function initMunchkin() {
    if(didInit === false) {
      didInit = true;
      Munchkin.init('558-NCX-702');
    }
    }
    var s = document.createElement('script');
    s.type = 'text/javascript';
    s.async = true;
    s.src = '//munchkin.marketo.net/munchkin.js';
    s.onreadystatechange = function() {
    if (this.readyState == 'complete' || this.readyState == 'loaded') {
      initMunchkin();
    }
    };
    s.onload = initMunchkin;
    document.getElementsByTagName('head')[0].appendChild(s);
  })();
}