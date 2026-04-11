This folder contains the draft website variant that is intentionally separate from the live GitHub Pages source in `site/`.

Notes:

- `site/` remains the deploy target for `.github/workflows/pages-site.yml`
- `site-draft/` is a local-only draft/staging copy
- To preview the draft locally, serve this folder as its own web root

Example:

```bash
cd site-draft
python3 -m http.server 4174
```
