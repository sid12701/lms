[Skip to main content](https://docs.mambu.com/api/pages/api-v2/responses/#__docusaurus_skipToContent_fallback)

On this page

> Error Response

```json
{

  "errorCode":"4",

  "errorSource":"Property scheduleSettings.repaymentInstallments may not be null",

  "errorReason":"INVALID_PARAMETERS"

}
```

The response to a request will contain either an error response or a [payload in the content type](https://docs.mambu.com/api/pages/api-v2/payloads/) that the endpoint accepts.

## Error response [​](https://docs.mambu.com/api/pages/api-v2/responses/\#error-response "Direct link to Error response")

An error response will consist of:

| Field | Type | Availability | Content |
| --- | --- | --- | --- |
| `errorCode` | number | Always present | A unique error code. For more information, see API Responses and Error Codes. |
| `errorSource` | string | Sometimes present | A human-readable message capturing unsatisfied constraints. |
| `errorReason` | string | Always present | A human-readable message stating the general category of the failure. |

The contents of the `errorSource` and `ErrorResponse` fields are subject to change without backwards compatibility.

- [Error response](https://docs.mambu.com/api/pages/api-v2/responses/#error-response)

×