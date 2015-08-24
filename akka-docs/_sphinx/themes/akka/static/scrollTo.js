jQuery(document).ready(function ($) {

  $(".scroll").click(function (event) {
    event.preventDefault();
    window.location.hash = $(this).attr('href');
    $(this.hash).effect("highlight", {color: "#15A9CE"}, 2000);
  });
});