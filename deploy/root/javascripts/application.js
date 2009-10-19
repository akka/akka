var count = 0;
var app = {
   url: '/chat',
   initialize: function() {
      $('login-name').focus();
      app.listen();
   },
   listen: function() {
      $('comet-frame').src = app.url + '?' + count;
      count ++;
   },
   login: function() {
      var name = $F('login-name');
      if(! name.length > 0) {
	 $('system-message').style.color = 'red';
	 $('login-name').focus();
	 return;
      }
      $('system-message').style.color = '#2d2b3d';
      $('system-message').innerHTML = name + ':';

      $('login-button').disabled = true;
      $('login-form').style.display = 'none';
      $('message-form').style.display = '';

      var query =
	 'action=login' +
	 '&name=' + encodeURI($F('login-name'));
      new Ajax.Request(app.url, {
	 postBody: query,
	 onSuccess: function() {
	    $('message').focus();
	 }
      });
   },
   post: function() {
      var message = $F('message');
      if(!message > 0) {
	 return;
      }
      $('message').disabled = true;
      $('post-button').disabled = true;

      var query =
	 'action=post' +
	 '&name=' + encodeURI($F('login-name')) +
	 '&message=' + encodeURI(message);
      new Ajax.Request(app.url, {
	 postBody: query,
	 onComplete: function() {
	    $('message').disabled = false;
	    $('post-button').disabled = false;
	    $('message').focus();
	    $('message').value = '';
	 }
      });
   },
   update: function(data) {
      var p = document.createElement('p');
      p.innerHTML = data.name + ':<br/>' + data.message;
      
      $('display').appendChild(p);

      new Fx.Scroll('display').down();
   }
};
var rules = {
   '#login-name': function(elem) {
      Event.observe(elem, 'keydown', function(e) {
	 if(e.keyCode == 13) {
	    $('login-button').focus();
	 }
      });
   },
   '#login-button': function(elem) {
      elem.onclick = app.login;
   },
   '#message': function(elem) {
      Event.observe(elem, 'keydown', function(e) {
	 if(e.shiftKey && e.keyCode == 13) {
	    $('post-button').focus();
	 }
      });
   },
   '#post-button': function(elem) {
      elem.onclick = app.post;
   }
};
Behaviour.addLoadEvent(app.initialize);
Behaviour.register(rules);
