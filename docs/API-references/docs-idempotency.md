[Skip to main content](https://docs.mambu.com/api/pages/api-v2/idempotency/#__docusaurus_skipToContent_fallback)

On this page

- shell
- http
- js
- ruby
- python
- java
- go

```shell
# You can also use wget

curl -X POST https://TENANT_NAME.mambu.com/api/deposits/{depositAccountId}/transactions \

  -H 'Content-Type: application/json' \

  -H 'Accept: application/vnd.mambu.v2+json' \

  -H 'Idempotency-Key: 01234567-9abc-def0-1234-56789abcdef0'
```

```http
POST https://TENANT_NAME.mambu.com/api/deposits/{depositAccountId}/transactions HTTP/1.1

Host: TENANT_NAME.mambu.com

Content-Type: application/json

Accept: application/vnd.mambu.v2+json

Idempotency-Key: 01234567-9abc-def0-1234-56789abcdef0
```

```javascript
var headers = {

  'Content-Type':'application/json',

  'Accept':'application/vnd.mambu.v2+json',

  'Idempotency-Key':'01234567-9abc-def0-1234-56789abcdef0'

};

$.ajax({

  url: 'https://TENANT_NAME.mambu.com/api/deposits/{depositAccountId}/transactions',

  method: 'post',

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

  'Content-Type' => 'application/json',

  'Accept' => 'application/vnd.mambu.v2+json',

  'Idempotency-Key' => '01234567-9abc-def0-1234-56789abcdef0'

}

result = RestClient.post 'https://TENANT_NAME.mambu.com/api/deposits/{depositAccountId}/transactions',

  params: {

  }, headers: headers

p JSON.parse(result)
```

```python
import requests

headers = {

  'Content-Type': 'application/json',

  'Accept': 'application/vnd.mambu.v2+json',

  'Idempotency-Key': '01234567-9abc-def0-1234-56789abcdef0'

}

r = requests.post('https://TENANT_NAME.mambu.com/api/deposits/{depositAccountId}/transactions', params={

}, headers = headers)

print r.json()
```

```java
URL obj = new URL("https://TENANT_NAME.mambu.com/api/deposits/{depositAccountId}/transactions");

HttpURLConnection con = (HttpURLConnection) obj.openConnection();

String idempotentKey = UUID.randomUUID().toString();

con.setRequestProperty(“Idempotency-Key”, idempotentKey);

con.setRequestProperty(“Accept”, “application/vnd.mambu.v2+json”);

con.setRequestMethod("POST");

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

        "Content-Type": []string{"application/json"},

        "Accept": []string{"application/vnd.mambu.v2+json"},

        "Idempotency-Key": []string{"01234567-9abc-def0-1234-56789abcdef0"},

    }

    data := bytes.NewBuffer([]byte{jsonReq})

    req, err := http.NewRequest("POST", "https://TENANT_NAME.mambu.com/api/deposits/{depositAccountId}/transactions", data)

    req.Header = headers

    client := &http.Client{}

    resp, err := client.Do(req)

    // ...

}
```

[Idempotent](https://en.wikipedia.org/wiki/Idempotence) requests to the Mambu API are guaranteed to be executed no more than once.

Working with financial transactions, it is important to ensure some requests are not repeated for any reason, which may result in data loss or accidental duplication. You may configure requests to be idempotent by providing an `Idempotency-Key` header and providing a unique UUID token for the request. This will guard against the possibility of error if a request must be retried.

When an idempotent request is processed, the status code and body of the response is associated with the idempotency key and stored in a cache. If the request is duplicated for any reason, the duplicate request will not be processed, and the response will be re-sent to the client.

warning

Idempotency keys expire after six hours in Mambu API v2. After this time period, requests using duplicate keys will be treated as new requests.

warning

Mambu is moving towards returning 409 response code instead of 102 response code, when there already is an in progress call with the same idempotency key.

Idempotent requests that fail at the server level such validation failures are not stored in the cache. Subsequent requests with the same idempotency key will be processed as if they were new, receiving the same `400 BAD REQUEST` status code.

For examples of idempotent requests, see the code samples for this section.

note

We do not recommend using idempotency for GET and DELETE requests, as such requests are naturally idempotent.

## Idempotency Keys and Retry Mechanisms [​](https://docs.mambu.com/api/pages/api-v2/idempotency/\#idempotency-keys-and-retry-mechanisms "Direct link to Idempotency Keys and Retry Mechanisms")

Idempotency keys **must** use the [v4 UUID format](https://www.itu.int/rec/T-REC-X.667-201210-I/en) (32 hexadecimal digits, in a 8-4-4-4-12 arrangement, such as `01234567-9abc-def0-1234-56789abcdef0`).

We recommend generating UUIDs programmatically or by using an online generator such as [UUID Generator](https://www.uuidgenerator.net/version4) to ensure that all keys are valid V4 UUIDs.

Retry mechanisms must:

- Use the same key for initial calls and retries.
- Retry at a reasonable frequency so as not to overload the API.
- Properly identify and handle error codes.

- [Idempotency Keys and Retry Mechanisms](https://docs.mambu.com/api/pages/api-v2/idempotency/#idempotency-keys-and-retry-mechanisms)

×