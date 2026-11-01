# Shelf image proxy

AVIF cover proxy for the Shelf app at `shelf-img.tn3w.dev`, behind Cloudflare. The app
asks it instead of Open Library, DNB, DIGAR and Bookbites when "Fast covers" is on
(Android 12+).

The builder publishes `covers.bin` (work id to cover source, per language) with every
`catalogue-*` release. The proxy loads the newest one (checked every 30 minutes), fetches
the cover from the address and with the user agent the app uses, trims scanner borders,
drops placeholders and serves AVIF.

## Routes

| Path | Answer |
| --- | --- |
| `/b/{release}/{language}/{work}/{160,320,640}.avif` | Book cover, 2:3 box |
| `/a/{author}/{96,192}.avif` | Open Library author photo, square box |
| `/health` | Release label, 503 until an index is loaded |

The language is part of the path because one work id has a different cover per catalogue.
The release only keys the cache: a new release gets new addresses.

## Caching

| Case | `Cache-Control` |
| --- | --- |
| Image | `public, max-age=31536000, immutable` |
| No cover / unusable image | `public, max-age=86400, s-maxage=604800` |
| Malformed path | `public, max-age=3600, s-maxage=86400` |
| Source down or no index yet | `no-store`, 503 |

Every image has one spelling (no leading zeros, fixed widths, no query), so Cloudflare
caches it once. Encoded images are kept on disk by source address and width.

## Running

Listens on the systemd socket (fd 3) only. `SHELF_IMAGES_DATA` (default `data`) holds the
image cache.

```bash
cargo test
systemd-socket-activate -l /tmp/images.sock env SHELF_IMAGES_DATA=data target/release/images
```

Deployment: `deploy/deploy-shelf-img.sh` in the server repository.
