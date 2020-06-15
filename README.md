# Elastidiff

Elasticsearch diff tool.

## Installation

``` sh
$ brew tap jvia/tap
$ brew install elastidiff
```

## Usage

``` sh
$ elastidiff localhost:9200/src localhost:9200/dst | colordiff
```

Outputs:

``` diff
Changed: 13
--- src
+++ dst
@@ -2,3 +2,3 @@
   "overview" : "A man with a low IQ has accomplished great things in his life and been present during significant historic events—in each case, far exceeding what anyone imagined he could do. But despite all he has achieved, his one true love eludes him.",
-  "release_date" : "1994-07-06",
+  "release_date" : "1995-07-06",
   "genres" : "Comedy",
Added:   17
Deleted: 24
```

### Limit comparisons to specific result sets

``` sh
$ elastidiff localhost:9200/src localhost:9200/dst \
    --src-filter '{"term": {"genres": "Drama"}}' \
    --dst-filter '{"term": {"genres": "Drama"}}'
```

### Limit comparisons to specific fields in the result set

``` sh
$ elastidiff localhost:9200/src localhost:9200/dst \
    --src-includes "title" \
    --dst-includes "title"
```

### Print a document-by-document record with no diff information

``` sh
$ elastidiff localhost:9200/src localhost:9200/dst --print-same --no-print-diff
Same:    11
Same:    12
Changed: 13
Same:    14
Same:    15
Same:    16
Added:   17
Same:    18
Same:    20
Same:    21
Same:    22
Deleted: 24
Same:    6
```

## Options

``` sh
Usage: elastidiff [options] src-url dst-url

The URL should be fully qualified and include the index. Example:
   http://my-es-host.com:9200/my-index

Options:
  -h, --help                         Prints this help.
  -c, --[no-]print-changed           Control printing diffs when documents have different data.
  -a, --[no-]print-added             Control printing when documents are missing from src but exist in dst.
  -d, --[no-]print-deleted           Control printing when documents are missing from dst but exit in src.
  -u, --[no-]print-same              Control printing when documents are the same.
      --[no-]print-diff              Control print diffs for chaned documents.
      --size SIZE              100   How many documents to fetch from Elasticsearch in a single request.
      --scroll-time SCROLL     1m    The duration to keep the scroll context open.
  -o, --output FORMAT          text  The kind of diff to print. It can be either `text` or `edn` (structural).
      --src-sort SORT          _id   How to sort the source index.
      --src-filter FITLER            A filter to constrain the result set.
      --src-includes INCLUDES  *     Elasticsearch source filter includes spec.
      --src-excludes EXCLUDES        Elasticsearch source filter excludes spec.
      --dst-sort SORT          _id   How to sort the source index.
      --dst-filter FITLER            A filter to constrain the result set.
      --dst-includes INCLUDES  *     Elasticsearch source filter includes spec.
      --dst-excludes EXLUDES         Elasticsearch source filter excludes spec.
```


## License

Copyright © 2020 Jeremiah Via

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
