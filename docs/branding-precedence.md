# Branding & customisation precedence (v1, agreed 2026-11-07)

One rule for every surface: Flows runner, session mode (Enroll/Authenticate),
hosted page; iOS, Android and web.

> **Per field, the most specific value that was explicitly set wins.
> Code beats the dashboard. Nothing counts unless it was explicitly set.**

## Layers (highest first)

| # | Layer | Where it comes from |
|---|-------|---------------------|
| 1 | Per-launch override (code) | Options passed when starting one Flow run / one session |
| 2 | SDK init config (code) | `UseSenseConfig.branding` / `appearance` / `copy` set at init |
| 3 | Dashboard, Flow level | The Flow's appearance/copy (Flows only) |
| 4 | Dashboard, brand/org level | The org's (or brand's) name, logo, colours, fonts, appearance, copy |
| 5 | Built-in defaults | SDK constants (DeepSense Blue, "Sense" wordmark, etc.) |

Where the SDK has no separate per-launch override, layer 1 and 2 are the same object.

## Rules

1. **Field by field, never object by object.** Each leaf field (primary colour,
   foreground, background, radius, font, logo URL, display name, each copy
   string, mode, ...) is resolved independently down the layers. Setting only a
   colour in code must not hide the dashboard's logo.
2. **Unset is unset.** A layer contributes a field only if it was explicitly set.
   - Server: send `null`/omit for anything the org/brand/Flow never configured.
     Stored column defaults (e.g. `organizations.primary_color = '#4f46e5'`) are
     NOT configuration and must be emitted as `null`.
   - SDKs: init-config fields default to `null` ("inherit"), never to a value.
     The SDK's own constants live only in layer 5.
3. **Identity follows the same order.** Logo URL and display name are fields like
   any other. Display rule (matches iOS today): show the logo alone when there is
   one; show the name only while the logo loads, or when there is no logo / it
   fails. Logo placement `none` hides the header.
4. **Session mode gets dashboard branding too.** The session-create response (and
   the init-session / hosted responses) carries the same `branding` object Flows
   already receive, so layers 3/4 exist in session mode.
5. **Resolve once, use everywhere.** Each surface resolves the effective branding
   once per run/session and every screen (runner, primer, capture, result,
   errors, loaders, spinners) reads from that resolved value. No screen reads a
   raw constant for a brandable token.
6. **Traceable.** With debug tracing on, the SDK logs the source layer of each
   resolved field (e.g. `primary=#FF6A00 (dashboard:org)`).

## Wire shape (server → SDK)

`branding` (same object for Flows run view, session create, init-session, hosted):

```json
{
  "display_name": "XTransfer" | null,
  "logo_url": "https://..." | null,
  "primary_color": "#FF6A00" | null,
  "redirect_url": null,
  "appearance": { ...FlowAppearance contract, only configured fields... } | null,
  "copy": { ...FlowCopy contract... } | null
}
```

`display_name` falls back to the org's legal/display name on the server only if
that name is a real configured value (it always is for an org). Everything else
is null when not configured.

## Out of scope for v1
- Web SDK alignment (follow-up, same rules).
- Dashboard UI to show which fields are "set" vs inherited.
