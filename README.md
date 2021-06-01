Filter large lists of integers using ElasticSearch
===========================================

This filter plugin uses RoaringBitmap to allow efficient filtering with hundreds of thousands (even millions) on ElasticSearch.

Read more here: https://luis-sena.medium.com/improve-elasticsearch-filtering-performance-10x-using-this-plugin-8c6485516c1a


Installation
------------

In order to install a stable version of the plugin, 
run ElasticSearch's `plugin` utility:

    bin/elasticsearch-plugin install https://github.com/lsena/fastfilter-elasticsearch-plugin/releases/download/v7.10.1.1/fastfilter-elasticsearch-plugin-7.10.1.1.zip?raw=true

You need to choose the correct plugin version to match your ES version (you can find the available versions in the releases github page)

To install from sources (master branch), run:

    gradle clean build

then install with (use full path):

    Linux:
    bin/elasticsearch-plugin install file:/.../(plugin)/build/distributions/*.zip

    Windows:
    bin\elasticsearch-plugin install file:///c:/.../(plugin)/build/distributions/*.zip

More information here: https://www.elastic.co/guide/en/elasticsearch/plugins/current/plugin-management-custom-url.html

Usage
-----

```json
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

Python Example
-----


```python
from pyroaring import BitMap
import base64

from elasticsearch import Elasticsearch


if __name__ == "__main__":
    es = Elasticsearch()
    # in the real world, the bitmap serialization would be for a big list
    # and you could even compute them before and store them for later user
    bm = BitMap([10, 20, 35])
    
    # this will match "foo" in important field
    # but only for documents that have a filter_id value of 10, 20 or 35
    result = es.search(
        index="user-index", 
        body={
            "query": {
            "bool": {
              "must": [
                {
                  "match": {
                    "important_field": "foo"
                  }
                }
              ], 
              "filter": {
                "script": {
                  "script": {
                    "source": "fast_filter",
                    "lang": "fast_filter",
                    "params": {
                      "field": "_id",
                      "operation": "include",
                      "terms": base64.b64encode(BitMap.serialize(bm))
                    }
                  }
                }
              }
            }
          }
        }
    )
```
