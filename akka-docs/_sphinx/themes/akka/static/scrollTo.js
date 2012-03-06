jQuery(document).ready(function($) {

      $(".scroll").click(function(event){		
        event.preventDefault();
        $('html,body').animate({scrollTop:$(this.hash).offset().top}, 300);
        $('html,body').animate({scrollTop:$(this.hash).offset().top-=5}, 300);
        $(this.hash).effect("highlight", {color: "#FFCC85"}, 2000);
      });
});