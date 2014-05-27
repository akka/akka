jQuery(document).ready(function($) {

  $("#toc ul").each(function(){		
    var elem = $(this);
    if (elem.children().length == 0) {
      $(".contents-title").css("display","none");
    }
  });

});