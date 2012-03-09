// check to see if this document is on the akka.io server. If so, google analytics.
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
}