[Skip to main content](https://docs.mambu.com/api/pages/api-v2/pagination/#__docusaurus_skipToContent_fallback)

On this page

- shell
- http
- js
- ruby
- python
- java
- go
- response headers

```shell
# You can also use wget

curl -X GET https://TENANT_NAME.mambu.com/api/{Insert resource URI here}?offset=10&limit=10&paginationDetails=ON \

  -H 'Accept: application/vnd.mambu.v2+json'
```

```http
GET https://TENANT_NAME.mambu.com/api/Insert-Resource-URI-here?offset=10&limit=10&paginationDetails=ON HTTP/1.1

Host: TENANT_NAME.mambu.com

Accept: application/vnd.mambu.v2+json
```

```javascript
var headers = {

  'Accept':'application/vnd.mambu.v2+json'

};

$.ajax({

  url: 'https://TENANT_NAME.mambu.com/api/{Insert resource URI here}?offset=10&limit=10&paginationDetails=ON',

  method: '{HTTP Verb}',

  headers: headers,

  success: function(data) {

    console.log(JSON.stringify(data));

  }

})
```

```ruby
require 'rest-client'

require 'json'

headers = {

  'Accept' => 'application/vnd.mambu.v2+json'

}

result = RestClient.{HTTP Verb} 'https://TENANT_NAME.mambu.com/api/{Insert resource URI here}?offset=10&limit=10&paginationDetails=ON',

  params: {

  }, headers: headers

p JSON.parse(result)
```

```python
import requests

headers = {

  'Accept': 'application/vnd.mambu.v2+json'

}

r = requests.{HTTP Verb}('https://TENANT_NAME.mambu.com/api/{Insert resource URI here}?offset=10&limit=10&paginationDetails=ON', params={

}, headers = headers)

print r.json()
```

```java
URL obj = new URL("https://TENANT_NAME.mambu.com/api/{Insert resource URI here}?offset=10&limit=10&paginationDetails=ON");

HttpURLConnection con = (HttpURLConnection) obj.openConnection();

con.setRequestProperty(“Accept”, “application/vnd.mambu.v2+json”);

con.setRequestMethod("{HTTP Verb}");

int responseCode = con.getResponseCode();

BufferedReader in = new BufferedReader(

    new InputStreamReader(con.getInputStream()));

String inputLine;

StringBuffer response = new StringBuffer();

while ((inputLine = in.readLine()) != null) {

    response.append(inputLine);

}

in.close();

System.out.println(response.toString());
```

```go
package main

import (

       "bytes"

       "net/http"

)

func main() {

    headers := map[string][]string{

        "Accept": []string{"application/vnd.mambu.v2+json"},

    }

    data := bytes.NewBuffer([]byte{jsonReq})

    req, err := http.NewRequest("{HTTP Verb}", "https://TENANT_NAME.mambu.com/api/{Insert resource URI here}?offset=10&limit=10&paginationDetails=ON", data)

    req.Header = headers

    client := &http.Client{}

    resp, err := client.Do(req)

    // ...

}
```

```text
(...)

content-type: application/vnd.mambu.v2+json

date: Mon, 03 Sep 2018 10:56:15 GMT

items-limit: 10

items-offset: 10

items-total: 35

(...)
```

Mambu API v2 has customisable pagination capabilities which can be used with all `GET` requests. It also has sorting and filtering capabilities for search endpoints.

We strongly recommend using the pagination, sorting, and filtering capabilities when making requests which will return a large number of records because response times are much faster.

## Pagination query parameters [​](https://docs.mambu.com/api/pages/api-v2/pagination/\#pagination-query-parameters "Direct link to Pagination query parameters")

Pagination is deactivated by default and must be specified in each request. There are three available query parameters you may use, `paginationDetails`, `offset`, and `limit`.

## `paginationDetails` [​](https://docs.mambu.com/api/pages/api-v2/pagination/\#paginationdetails "Direct link to paginationdetails")

The `paginationDetails` query parameter returns pagination details in the header of your response. The default value is `OFF`. To include pagination details set the value to `ON`. Pagination details includes the total number of records, the limit value, and the offset value.

warning

Please Note

`PaginationDetails=ON` should only be used for the initial query. For all subsequent queries, for example, when paging through the results, we strongly advise to either pass `PaginationDetails=OFF`, or not include this parameter. If you request pagination details for all requests, there may be significant performance issues leading to higher response times.

## `limit` [​](https://docs.mambu.com/api/pages/api-v2/pagination/\#limit "Direct link to limit")

The `limit` query parameter determines the number of records that will be retrieved. The default value is 50. The maximum value is 1,000.

## `offset` [​](https://docs.mambu.com/api/pages/api-v2/pagination/\#offset "Direct link to offset")

The `offset` query parameter determines how many records will be skipped before being included in the returned results. The default value is 0.

In the example, we can see that there are 35 total records. By setting `offset` to 10 and `limit` to 10, we are returning the second set of 10 records, essentially "Page 2" of our paginated response.

## Pagination best practices [​](https://docs.mambu.com/api/pages/api-v2/pagination/\#pagination-best-practices "Direct link to Pagination best practices")

Once you have used the pagination query parameters to retrieve all the available records in the database for your specific query, you no longer need to make any additional API requests.

To determine whether you need to make any additional API requests you may compare the value of the `limit` parameter to the number of records retrieved in the body of your request.

If the number of records is less than the value of the `limit` parameter then no additional API requests are necessary.

If the number of records is equal to the `limit` value then you may make additional API requests.

If you receive an empty array `[]` in the body of your request, this means there are no records for that request and you do not need to make any additional API requests.

## Sorting and filtering with search endpoints [​](https://docs.mambu.com/api/pages/api-v2/pagination/\#sorting-and-filtering-with-search-endpoints "Direct link to Sorting and filtering with search endpoints")

All the search endpoints in API v2 end in `:search`. Search endpoints accept a `filterCriteria` array of objects and a `sortingCriteria` object in their request body.

When making broad searches that will return a lot of records, using pagination with appropriate values can ensure that your result set will not shift as new records matching your search criteria are created, which may otherwise lead to duplicates across pages.

### `sortingCriteria` [​](https://docs.mambu.com/api/pages/api-v2/pagination/\#sortingcriteria "Direct link to sortingcriteria")

The `sortingCriteria` object has two properties, `field` and `order`.

**`field` property**

We recommend you enter either an incremental ID or a timestamp as the value for the `field` property.

**`order` property**

The order property accepts two values, ascending (`ASC`) or descending (`DESC`). The default value is `DESC`, however, if using pagination or a search where new records are being actively created, for example transactions or journal entries created up to and including the current day, we strongly recommend you set the value to `ASC`. This will cause new records to be added to the end of your result set.

### `filterCriteria` [​](https://docs.mambu.com/api/pages/api-v2/pagination/\#filtercriteria "Direct link to filtercriteria")

If you are making a broad search that will return a lot of results, we recommend constraining your search query using a time interval. This may be done by setting the `field` property to a date property such as `creationDate` or `valueDate`. Setting the `operator` property to `BETWEEN`. And entering two dates as the values for the `value` and `secondValue` properties. This will ensure that no newly created records will interfere with a set of results. Please see the related note about searches using the `BETWEEN` operator in the [considerations for specific field types](https://docs.mambu.com/api/pages/api-v2/considerations-for-specific-field-types/) section below for more information about this operator.

- [Pagination query parameters](https://docs.mambu.com/api/pages/api-v2/pagination/#pagination-query-parameters)
- [`paginationDetails`](https://docs.mambu.com/api/pages/api-v2/pagination/#paginationdetails)
- [`limit`](https://docs.mambu.com/api/pages/api-v2/pagination/#limit)
- [`offset`](https://docs.mambu.com/api/pages/api-v2/pagination/#offset)
- [Pagination best practices](https://docs.mambu.com/api/pages/api-v2/pagination/#pagination-best-practices)
- [Sorting and filtering with search endpoints](https://docs.mambu.com/api/pages/api-v2/pagination/#sorting-and-filtering-with-search-endpoints)
  - [`sortingCriteria`](https://docs.mambu.com/api/pages/api-v2/pagination/#sortingcriteria)
  - [`filterCriteria`](https://docs.mambu.com/api/pages/api-v2/pagination/#filtercriteria)

×