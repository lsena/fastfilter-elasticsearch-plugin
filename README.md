# fastfilter-elasticsearch-plugin
Filter large lists with Elasticsearch using Roaringbitmap

## query example
```
GET /test/_search
{
  "query": {
    "bool": {
      "filter": {
        "script": {
          "script": {
            "source": "fast_filter",
            "lang": "fast_filter",
            "params": {
              "field": "filter_id",
              "operation": "include",
              "terms": "OjAAAAEAAAAAAAIAEAAAAAoAFAAjAA=="
            }
          }
        }
      }
    }
  }
}
```
This will fetch the documents that have a filter_id with the values 10, 20 or 35
