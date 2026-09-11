# Ric Browser

Lightweight Android WebView browser with AdblockAndroid network filtering, popup protection, Google-compatible cookies/storage, and a YouTube ad-handling layer.

The ad engine uses AdblockAndroid because it supports EasyList/AdGuard-compatible filters, element hiding, CSS and scriptlet rules. YouTube-specific handling is an additional compatibility layer; it is not a promise of permanent 100% ad removal because YouTube can change its player and delivery mechanisms.

## Current routing and ad protection

- Direct media action opens `com.ric.player.PlayerActivity` explicitly.
- The direct RIC Player action no longer falls back to MX Player.
- WebView request filtering blocks common advertising hosts and ad URL patterns before they render.
- A persistent DOM cleaner removes common ad containers, intrusive banners, popups, and gambling-ad creatives that are injected after page load.
