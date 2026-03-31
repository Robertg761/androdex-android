# Privacy Policy

Current repo source:

- `Docs/PRIVACY_POLICY.md`

Target public URL:

- `https://androdex.xyz/privacy-policy/`

Pages site source:

- `site/privacy-policy/index.html`
- `.github/workflows/pages-site.yml`

Deployment notes:

- The repo is prepared for GitHub Pages deployment from a GitHub Actions workflow.
- The repository Pages settings are already configured for a GitHub Actions build with the custom domain `androdex.xyz`.
- DNS for the apex domain still needs to point to GitHub Pages before HTTPS enforcement will succeed.
- When publishing from a custom GitHub Actions workflow, GitHub ignores any `CNAME` file, so the custom domain must be set in Pages settings or via the Pages API.

Suggested DNS records for `androdex.xyz`:

- `A` `@` -> `185.199.108.153`
- `A` `@` -> `185.199.109.153`
- `A` `@` -> `185.199.110.153`
- `A` `@` -> `185.199.111.153`
- `AAAA` `@` -> `2606:50c0:8000::153`
- `AAAA` `@` -> `2606:50c0:8001::153`
- `AAAA` `@` -> `2606:50c0:8002::153`
- `AAAA` `@` -> `2606:50c0:8003::153`
- Optional: `CNAME` `www` -> `robertg761.github.io`

Recommended order:

1. Add the DNS records above at your registrar or DNS provider.
2. Verify the domain in your GitHub account settings for takeover protection.
3. Push the Pages workflow and `site/` content to `main` so GitHub can deploy the policy site.
4. Enable HTTPS enforcement after DNS has propagated and the certificate is ready.

Before submission, verify that the exact public URL you enter in Play Console is:

- publicly reachable without login
- stable
- the same policy text shipped in the app and referenced in the listing
