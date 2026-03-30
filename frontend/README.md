# LMS Frontend

React + Vite frontend for the Bhawana LMS operations console.

## Current Scope

- bootstrap JWT login against `/api/v1/auth/token`
- system context hydration from `/api/v1/internal/system/context`
- LSP administration backed by `/api/v1/internal/admin/lsps`
- user administration backed by `/api/v1/internal/admin/users`
- form metadata from `/api/v1/internal/admin/metadata`

## Local Commands

- `npm run dev`
- `npm run build`
- `npm run lint`

## Notes

- Set `VITE_API_BASE_URL` if the backend is not running on `http://localhost:8080`.
- The current auth flow stores the access token in local storage for local development.
