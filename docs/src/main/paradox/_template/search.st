$if(page.properties.("algolia.docsearch.api_key"))$
<!-- Algolia docs search -->
<script type="text/javascript" src="https://cdn.jsdelivr.net/docsearch.js/2/docsearch.min.js"></script>
<style>.algolia-autocomplete { display: block !important }</style>
<script type="text/javascript">
  var version = "$page.properties.("project.version")$";

  docsearch({
    apiKey: '$page.properties.("algolia.docsearch.api_key")$',
    indexName: '$page.properties.("algolia.docsearch.index_name")$',
    inputSelector: '#search',
    algoliaOptions: {
      hitsPerPage: 5,
      facetFilters: ["version:" + version]
    }
  });
  docsearch({
    apiKey: '$page.properties.("algolia.docsearch.api_key")$',
    indexName: '$page.properties.("algolia.docsearch.index_name")$',
    inputSelector: '#overlay-search',
    algoliaOptions: {
      hitsPerPage: 5,
      facetFilters: ["version:" + version]
    }
  });
  // set up "/" as global shortcut for focusing on search
  jQuery(document).keypress(function (event) {
    if (event.keyCode == 47) {
      jQuery("#search").focus();
      return false; // swallow key event, otherwise the / char would be input into the search box
    }
  });
</script>
$endif$