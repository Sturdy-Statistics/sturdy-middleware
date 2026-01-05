# sturdy-middleware

**Small, focused Ring middleware for security and observability.**

This library collects a set of middleware components used across Sturdy Statistics web services.
Each middleware does **one thing**, is **explicit**, and is intended to be composed deliberately rather than enabled wholesale.

This library reflects the needs and opinions of Sturdy Statistics.
We may not accept feature requests that dilute its focus.

These are not intended to be framework defaults; rather, they encode specific operational and security decisions that we want to apply consistently across our applications.

## Design principles

- **Explicit code**
  Middleware should make policy obvious at the call site.

- **Idempotent and safe**
  Middleware should be safe to apply once or multiple times without surprising behavior.

- **Fail closed where appropriate**
  Deny on missing or ambiguous input, especially for auth-related or state-changing endpoints.

- **Cheap by default**
  No unnecessary allocation, parsing, or reflection on hot paths.

## Middleware overview

| Middleware                        | Purpose                              | Typical scope               |
|-----------------------------------|--------------------------------------|-----------------------------|
| `wrap-request-id`                 | Stable request correlation           | **All requests**            |
| `wrap-max-request-size`           | Reject oversized uploads early       | Upload / API endpoints      |
| `wrap-require-same-origin`        | CSRF protection (tolerant)           | Authenticated endpoints     |
| `wrap-require-same-origin-strict` | CSRF protection (strict)             | Auth endpoints              |
| `wrap-nostore`                    | Prevent caching of private responses | Logged-in pages, dashboards |
| `wrap-nostore-on-errors`          | Prevent caching of error responses   | **All requests**            |
| `with-vary-cookie`                | Prevent cache mixing across users    | Logged-in pages             |
| `with-noindex`                    | Prevent indexing by search engines   | Private or internal pages   |

## `wrap-request-id`

**Attach a request identifier to every request and response.**

- Reads trusted incoming headers (`X-Request-Id`, `X-Correlation-Id`, `traceparent`)
- Validates and normalizes IDs (length-capped, allow-listed characters)
- Falls back to a generated UUID
- Attaches the ID to:
  - `:request-id` in the Ring request
  - a response header (default: `X-Request-Id`)

### Use this:
- **On every request**
- At the **outermost layer** of your middleware stack
- To correlate logs, traces, and error reports

```clj
(wrap-request-id handler)
```

## `wrap-max-request-size`

**Reject requests with a `Content-Length` exceeding a configured limit.**

- Returns HTTP **413 Payload Too Large**
- Closes the connection early
- Avoids buffering large request bodies unnecessarily
- Allows requests with missing or invalid `Content-Length`

### Use this:
- On upload endpoints
- On APIs that accept large payloads
- Early in the middleware stack

```clj
(wrap-max-request-size handler (* 10 1024 1024)) ; 10 MB
```

## Same-origin enforcement

### `wrap-require-same-origin` (tolerant)

- Enforces same-origin on POST/PUT/PATCH/DELETE
- Allows missing `Origin` header
- Suitable for most authenticated routes

```clj
(wrap-require-same-origin handler)
```

### `wrap-require-same-origin-strict`

- Enforces same-origin on POST/PUT/PATCH/DELETE
- Rejects missing `Origin`
- Use for sensitive state changes

```clj
(wrap-require-same-origin-strict handler)
```

## Cache control

### `wrap-nostore`

Prevents caching of user-specific or sensitive responses.

```clj
(wrap-nostore handler)
```

### `wrap-nostore-on-errors`

Applies no-store headers to error responses and mutable requests.

```clj
(wrap-nostore-on-errors handler)
```

### `with-vary-cookie`

Ensures responses vary on `Cookie` without duplication.

```clj
(with-vary-cookie response)
```

## Robots / indexing control

### `with-noindex`

Prevents search engines from indexing private pages.

```clj
(with-noindex response)
```

## Typical middleware stacks

### Authenticated routes

```clj
(-> handler
    wrap-request-id
    wrap-max-request-size
    wrap-require-same-origin
    wrap-nostore-on-errors
    wrap-nostore)
```

### High-risk endpoints

```clj
(-> handler
    wrap-request-id
    wrap-require-same-origin-strict
    wrap-nostore)
```

## Why this exists

This library exists to centralize **security-affecting middleware** so that:

- policies are consistent across services
- changes are reviewed once, not copy-pasted everywhere
- security posture is explicit and auditable

## Security posture summary

- Defensive handling of user-supplied headers
- Conservative cache control for private data
- Explicit same-origin enforcement
- Early rejection of oversized payloads
- End-to-end request correlation

Designed for production use in long-running services.

## **Customizing the 413 error page**

`wrap-max-request-size` exposes a **dynamic rendering hook** that applications may rebind to integrate with their own error views.

### **Default behavior**

By default, oversized requests return:

* HTTP **413 Payload Too Large**
* `Connection: close`
* A minimal, self-contained HTML error page

This default is safe and dependency-free, but is likely too basic for production use.
If you use this library, you will want to re-bind it.

### **Rebinding the error view**

Applications may rebind `*render-too-large*` to customize either the response body or entire response.

The hook is called with a context map containing:

```
{:code 413
 :title "Content too large"
 :blurb "Upload failed."
 :message "... human-readable explanation ..."
 :request-id "abc123"          ; if present
 :request <ring-request-map>
 :max-upload-bytes 10485760}
```

The function may return **either**:

* a Ring response map
* a response body (string / bytes / stream), which will be wrapped automatically

### **Example: integrate with an application error page**

```
(require
 '[sturdy.middleware.request-size :as rs]
 '[ring-errors.views :as err-v])

(def app
  (binding [rs/*render-too-large*
            (fn [{:keys [message request-id]}]
              (err-v/error-page
               {:code 413
                :title "Content too large"
                :blurb "Upload failed."}
               {:message message
                :id request-id}))]
    (-> handler
        (rs/wrap-max-request-size (* 10 1024 1024)))))
```

### **Global rebinding**

If you prefer a single global configuration:

```
(alter-var-root
 #'rs/*render-too-large*
 (constantly
  (fn [ctx]
    (my-custom-413-view ctx))))
```

### **API / JSON responses**

The render hook can inspect request headers (e.g. `Accept`) and return JSON for API clients while keeping HTML for browsers.

```
(fn [{:keys [request message]}]
  (if (= "application/json"
         (get-in request [:headers "accept"]))
    {:status 413
     :headers {"Content-Type" "application/json"}
     :body {:error "payload-too-large"
            :message message}}
    (html-view message)))
```


## License

Apache License 2.0

Copyright © Sturdy Statistics

<!-- Local Variables: -->
<!-- fill-column: 10000 -->
<!-- End: -->
