[Skip to main content](https://docs.mambu.com/api/api-v2/documents/schemas/documentuploadschema/#__docusaurus_skipToContent_fallback)

# Documentuploadschema

**file** binaryrequired

The file to be attached for a client.

**id** string

The ID or encoded key of the entity that owns the document. The ID or encoded key must belong to the entity indicated by the `entity` parameter. Possible entity types are :`CLIENT`, `GROUP`, `LOAN_PRODUCT`, `SAVINGS_PRODUCT`, `CENTRE`, `BRANCH`, `USER`, `LOAN_ACCOUNT`, `DEPOSIT_ACCOUNT`, `ID_DOCUMENT`, `LINE_OF_CREDIT`, `GL_JOURNAL_ENTRY`. If the entity is `GL_JOURNAL_ENTRY`, the value can also represent the Journal Entry Transaction ID.

**name** string

The name (title) of the attached file.

**notes** string

The description of the attached file.

**ownerType** string

The type of the owner of the document.

Documentuploadschema

```json
{

  "file": "string",

  "id": "string",

  "name": "string",

  "notes": "string",

  "ownerType": "string"

}
```

×